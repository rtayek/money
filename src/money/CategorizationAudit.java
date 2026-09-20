package money;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits how Plaid transactions map onto Simplifi transactions. A match requires
 * the same merchant token and amount, opposite source-specific signs, and dates
 * within two days. Each row can be used only once; tied candidates are reported
 * as ambiguous instead of silently counted as a clean match.
 *
 * Format A (Simplifi all.csv): Date,Account,Check #,Reviewed,Payee,Category,Exclusion,Amount
 * Format B (Plaid/AmEx):       Date,Name,MerchantName,OriginalDescription,Category,Amount
 *
 * Run: java -p out -m money/money.CategorizationAudit [quickenCsv] [plaidCsv]
 * (defaults are in main). The run is passive: it reads the files and prints;
 * it writes nothing.
 */
public final class CategorizationAudit {

    public static void main(String[] args) {
        Path quicken = Path.of(args.length > 0 ? args[0] : "all.csv");
        Path plaid = Path.of(args.length > 1 ? args[1] : "private/plaid/combined.csv");
        new CategorizationAudit(quicken, plaid).run();
    }

    public CategorizationAudit(Path quickenCsv, Path plaidCsv) {
        this.quickenCsv = quickenCsv;
        this.plaidCsv = plaidCsv;
    }

    public void run() {
        List<Entry> quicken = load(quickenCsv,
                formatADate, formatAPayee, formatAAmount, true);
        List<Entry> plaid = load(plaidCsv,
                formatBDate, formatBText, formatBAmount, false);
        Audit audit = match(quicken, plaid);
        printAudit(audit, quicken.size(), plaid.size());
    }

    static String cleanPayee(String raw) {
        if (raw == null) return "";
        String upper = raw.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        String noPrefix = processorPrefix.matcher(upper).replaceFirst("");
        Matcher m = leadingToken.matcher(noPrefix);
        return m.find() ? m.group(1) : noPrefix;
    }

    private static Audit match(List<Entry> quicken, List<Entry> plaid) {
        boolean[] usedQuicken = new boolean[quicken.size()];
        List<Match> matches = new ArrayList<>();
        List<Entry> unmatchedPlaid = new ArrayList<>();

        for (Entry p : plaid) {
            long bestDays = Long.MAX_VALUE;
            List<Integer> best = new ArrayList<>();
            for (int i = 0; i < quicken.size(); i++) {
                if (usedQuicken[i]) continue;
                Entry q = quicken.get(i);
                if (!candidate(q, p)) continue;
                long days = Math.abs(ChronoUnit.DAYS.between(q.date(), p.date()));
                if (days < bestDays) {
                    bestDays = days;
                    best.clear();
                    best.add(i);
                } else if (days == bestDays) {
                    best.add(i);
                }
            }

            if (best.isEmpty()) {
                unmatchedPlaid.add(p);
                continue;
            }

            int chosen = best.get(0);
            usedQuicken[chosen] = true;
            matches.add(new Match(quicken.get(chosen), p, best.size() > 1));
        }
        return new Audit(matches, unmatchedPlaid);
    }

    private static boolean candidate(Entry quicken, Entry plaid) {
        if (quicken.date() == null || plaid.date() == null) return false;
        if (quicken.cleaned().isEmpty() || !quicken.cleaned().equals(plaid.cleaned())) return false;
        if (Math.abs(Math.abs(quicken.amount()) - Math.abs(plaid.amount())) > amountTolerance) {
            return false;
        }
        if (!sameTransactionDirection(quicken.amount(), plaid.amount())) return false;
        return Math.abs(ChronoUnit.DAYS.between(quicken.date(), plaid.date()))
                <= dateToleranceDays;
    }

    /**
     * Simplifi records spending as negative while Plaid records spending as
     * positive, so corresponding transactions normally have opposite signs.
     */
    private static boolean sameTransactionDirection(double simplifiAmount, double plaidAmount) {
        if (simplifiAmount == 0 || plaidAmount == 0) {
            return simplifiAmount == 0 && plaidAmount == 0;
        }
        return Math.signum(simplifiAmount) == -Math.signum(plaidAmount);
    }

    private static void printAudit(Audit audit, int quickenCount, int plaidCount) {
        String fmt = "%-12s  %-20s  %-12s  %11s  %-10s  %-34s%n";
        System.out.printf(fmt, "DATE", "SIMPLIFI PAYEE", "TOKEN", "AMOUNT", "STATUS", "PLAID DESCRIPTION");
        System.out.printf(fmt, dashes(12), dashes(20), dashes(12), dashes(11), dashes(10), dashes(34));

        int ambiguous = 0;
        for (Match m : audit.matches()) {
            if (m.ambiguous()) ambiguous++;
            Entry q = m.quicken();
            System.out.printf(Locale.US, fmt,
                    q.date(),
                    trunc(q.rawSource(), 20),
                    trunc(q.cleaned(), 12),
                    String.format(Locale.US, "%.2f", q.amount()),
                    m.ambiguous() ? "AMBIGUOUS" : "MATCH",
                    trunc(m.plaid().rawSource(), 34));
        }

        int cleanMatches = audit.matches().size() - ambiguous;
        int failed = audit.unmatchedPlaid().size();
        double cleanRate = plaidCount == 0 ? 0 : 100.0 * cleanMatches / plaidCount;

        System.out.println();
        System.out.println("SCORECARD (one-to-one Plaid to Simplifi matching)");
        System.out.printf(Locale.US, "  PASS (unique match): %4d%n", cleanMatches);
        System.out.printf(Locale.US, "  AMBIGUOUS:           %4d%n", ambiguous);
        System.out.printf(Locale.US, "  FAIL (no match):     %4d%n", failed);
        System.out.printf(Locale.US, "  Clean match rate:    %5.1f%%%n", cleanRate);
        System.out.printf(Locale.US, "  Simplifi rows: %d, Plaid rows: %d%n",
                quickenCount, plaidCount);

        if (!audit.unmatchedPlaid().isEmpty()) {
            System.out.println("  Unmatched Plaid rows:");
            String failFmt = "    %-12s  %11s  %-12s  %s%n";
            for (Entry f : audit.unmatchedPlaid()) {
                System.out.printf(Locale.US, failFmt,
                        f.date(),
                        String.format(Locale.US, "%.2f", f.amount()),
                        trunc(f.cleaned(), 12),
                        trunc(f.rawSource(), 40));
            }
        }
    }

    private static List<Entry> load(Path csv, String dateHeader, String textHeader,
                                    String amountHeader, boolean simplifi) {
        List<String> lines = readAllLines(csv);
        if (lines.isEmpty()) return List.of();
        List<String> header = parseCsvLine(lines.get(0));
        int dateCol = indexOfHeader(header, dateHeader);
        int textCol = indexOfHeader(header, textHeader);
        int amtCol = indexOfHeader(header, amountHeader);
        int exclusionCol = simplifi ? indexOfHeader(header, "Exclusion") : -1;
        int merchantCol = simplifi ? -1 : indexOfHeader(header, "MerchantName");
        int nameCol = simplifi ? -1 : indexOfHeader(header, "Name");
        if (dateCol < 0 || textCol < 0 || amtCol < 0) {
            throw new IllegalStateException("Missing required CSV columns in " + csv + ": " + header);
        }

        List<Entry> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) continue;
            List<String> f = parseCsvLine(raw);
            if (simplifi && field(f, exclusionCol).equalsIgnoreCase("yes")) continue;

            LocalDate date = SpendingPlot.parseDate(field(f, dateCol));
            String text = field(f, textCol);
            if (!simplifi && text.isBlank()) text = field(f, merchantCol);
            if (!simplifi && text.isBlank()) text = field(f, nameCol);
            double amount = parseAmount(field(f, amtCol));
            rows.add(new Entry(date, text, cleanPayee(text), amount));
        }
        return rows;
    }

    private static String field(List<String> fields, int col) {
        return (col >= 0 && col < fields.size()) ? fields.get(col).strip() : "";
    }

    private static int indexOfHeader(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).strip().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static double parseAmount(String s) {
        String t = s.strip();
        if (t.isEmpty()) return 0;
        boolean negative = false;
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true;
            t = t.substring(1, t.length() - 1);
        }
        t = t.replace("$", "").replace(",", "").strip();
        if (t.startsWith("-")) {
            negative = true;
            t = t.substring(1);
        }
        if (t.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(t);
            return negative ? -v : v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    private static List<String> readAllLines(Path csv) {
        try {
            return Files.readAllLines(csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + csv, e);
        }
    }

    private static String dashes(int n) {
        return "-".repeat(n);
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + ".";
    }

    record Entry(LocalDate date, String rawSource, String cleaned, double amount) {}
    record Match(Entry quicken, Entry plaid, boolean ambiguous) {}
    record Audit(List<Match> matches, List<Entry> unmatchedPlaid) {}

    // ---- fields -------------------------------------------------------

    private static final String formatADate = "Date";
    private static final String formatAPayee = "Payee";
    private static final String formatAAmount = "Amount";

    private static final String formatBDate = "Date";
    private static final String formatBText = "OriginalDescription";
    private static final String formatBAmount = "Amount";

    private static final Pattern processorPrefix = Pattern.compile("^[A-Z]{2,5}\\*+\\s*");
    private static final Pattern leadingToken = Pattern.compile("^([A-Z0-9&'\\-]+)");

    private static final int dateToleranceDays = 2;
    private static final double amountTolerance = 0.005;

    private final Path quickenCsv;
    private final Path plaidCsv;
}
