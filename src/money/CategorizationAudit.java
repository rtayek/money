package money;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 1 audit. Ingests two financial CSV layouts, cleans the merchant text
 * with regex, builds a deterministic rule key of cleanedPayee + "_" + abs(amount),
 * and prints an aligned table predicting how Format A rows map onto Format B.
 *
 * Format A (Quicken all.csv): Date,Account,Check #,Reviewed,Payee,Category,Exclusion,Amount
 * Format B (Plaid/AmEx):      Date,Name,MerchantName,OriginalDescription,Category,Amount
 *
 * Run: java -p out -m money/money.CategorizationAudit [quickenCsv] [plaidCsv]
 * (defaults are in main). The run is passive: it reads the files and prints;
 * it writes nothing.
 */
public final class CategorizationAudit {

    private static final String FORMAT_A_DATE = "Date";
    private static final String FORMAT_A_PAYEE = "Payee";
    private static final String FORMAT_A_AMOUNT = "Amount";

    private static final String FORMAT_B_DATE = "Date";
    private static final String FORMAT_B_TEXT = "OriginalDescription";
    private static final String FORMAT_B_AMOUNT = "Amount";

    // Strip a leading payment-processor tag such as "TST*", "SQ*", "PAR*", "SC*".
    private static final Pattern PROCESSOR_PREFIX = Pattern.compile("^[A-Z]{2,5}\\*+\\s*");
    // Capture the leading merchant token and drop the trailing reference blob,
    // e.g. "AMAZON DIGIT*6693T8P888" -> "AMAZON", "GOOGLE *AI" -> "GOOGLE".
    private static final Pattern LEADING_TOKEN = Pattern.compile("^([A-Z0-9&'\\-]+)");

    public static void main(String[] args) {
        Path quicken = Path.of(args.length > 0 ? args[0] : "all.csv");
        Path plaid = Path.of(args.length > 1 ? args[1] : "tmp/plaid/combined.csv");
        new CategorizationAudit(quicken, plaid).run();
    }

    public CategorizationAudit(Path quickenCsv, Path plaidCsv) {
        this.quickenCsv = quickenCsv;
        this.plaidCsv = plaidCsv;
    }

    public void run() {
        List<Entry> formatA = load(quickenCsv, FORMAT_A_DATE, FORMAT_A_PAYEE, FORMAT_A_AMOUNT);
        List<Entry> formatB = load(plaidCsv, FORMAT_B_DATE, FORMAT_B_TEXT, FORMAT_B_AMOUNT);
        Map<String, List<Entry>> indexA = indexByKey(formatA);
        Map<String, List<Entry>> indexB = indexByKey(formatB);
        printAudit(formatA, formatB, indexB);
        printScorecard(formatA, formatB, indexA, indexB);
    }

    static String cleanPayee(String raw) {
        if (raw == null) return "";
        String upper = raw.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        String noPrefix = PROCESSOR_PREFIX.matcher(upper).replaceFirst("");
        Matcher m = LEADING_TOKEN.matcher(noPrefix);
        return m.find() ? m.group(1) : noPrefix;
    }

    static String ruleKey(String cleanedPayee, double amount) {
        return cleanedPayee + "_" + String.format(Locale.US, "%.2f", Math.abs(amount));
    }

    private static Map<String, List<Entry>> indexByKey(List<Entry> rows) {
        Map<String, List<Entry>> index = new LinkedHashMap<>();
        for (Entry e : rows) index.computeIfAbsent(e.key(), k -> new ArrayList<>()).add(e);
        return index;
    }

    private void printAudit(List<Entry> formatA, List<Entry> formatB, Map<String, List<Entry>> indexB) {
        String fmt = "%-13s  %-20s  %-12s  %11s  %-22s  %-34s%n";
        System.out.printf(fmt, "DATE", "SOURCE PAYEE (A)", "CLEANED", "AMOUNT", "RULE KEY", "PLAID PREDICTION (B)");
        System.out.printf(fmt, dashes(13), dashes(20), dashes(12), dashes(11), dashes(22), dashes(34));

        int matched = 0;
        for (Entry a : formatA) {
            List<Entry> hits = indexB.get(a.key());
            if (hits == null) continue;
            matched++;
            System.out.printf(fmt,
                    trunc(a.date(), 13),
                    trunc(a.rawSource(), 20),
                    trunc(a.cleaned(), 12),
                    String.format(Locale.US, "%.2f", a.amount()),
                    trunc(a.key(), 22),
                    trunc(hits.get(0).rawSource(), 34));
        }
        System.out.println();
        System.out.printf(Locale.US,
                "Format A rows: %d, Format B rows: %d, A rows matched to B by rule key: %d%n",
                formatA.size(), formatB.size(), matched);
    }

    private void printScorecard(List<Entry> formatA, List<Entry> formatB,
                                Map<String, List<Entry>> indexA, Map<String, List<Entry>> indexB) {
        int passB = 0;
        List<Entry> failsB = new ArrayList<>();
        for (Entry b : formatB) {
            if (indexA.containsKey(b.key())) passB++;
            else failsB.add(b);
        }
        int totalB = formatB.size();
        double rateB = totalB == 0 ? 0 : 100.0 * passB / totalB;

        int sharedKeys = 0;
        for (String k : indexB.keySet()) if (indexA.containsKey(k)) sharedKeys++;

        System.out.println();
        System.out.println("SCORECARD (does each Plaid row map onto a Quicken row by rule key?)");
        System.out.printf(Locale.US, "  PASS (matched):   %4d%n", passB);
        System.out.printf(Locale.US, "  FAIL (no match):  %4d%n", failsB.size());
        System.out.printf(Locale.US, "  Match rate:       %5.1f%%%n", rateB);
        System.out.printf(Locale.US, "  Rule keys: %d in A, %d in B, %d shared%n",
                indexA.size(), indexB.size(), sharedKeys);

        if (!failsB.isEmpty()) {
            System.out.println("  Unmatched Plaid rows (FAIL):");
            String fmt = "    %-13s  %11s  %-22s  %s%n";
            for (Entry f : failsB) {
                System.out.printf(Locale.US, fmt,
                        trunc(f.date(), 13),
                        String.format(Locale.US, "%.2f", f.amount()),
                        trunc(f.key(), 22),
                        trunc(f.rawSource(), 40));
            }
        }
    }

    private static List<Entry> load(Path csv, String dateHeader, String textHeader, String amountHeader) {
        List<String> lines = readAllLines(csv);
        if (lines.isEmpty()) return List.of();
        List<String> header = parseCsvLine(lines.get(0));
        int dateCol = indexOfHeader(header, dateHeader);
        int textCol = indexOfHeader(header, textHeader);
        int amtCol = indexOfHeader(header, amountHeader);

        List<Entry> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) continue;
            List<String> f = parseCsvLine(raw);
            String date = field(f, dateCol);
            String text = field(f, textCol);
            double amount = parseAmount(field(f, amtCol));
            String cleaned = cleanPayee(text);
            rows.add(new Entry(date, text, cleaned, amount, ruleKey(cleaned, amount)));
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

    record Entry(String date, String rawSource, String cleaned, double amount, String key) {}

    private final Path quickenCsv;
    private final Path plaidCsv;
}
