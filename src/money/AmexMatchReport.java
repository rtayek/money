package money;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Matches Simplifi's Amex rows in all.csv against the richer Plaid feed
 * (plaid-transactions.csv, from PullTransactions) by date and amount, to see
 * how often Plaid's Name/MerchantName/OriginalDescription say more than
 * Simplifi's generic Payee ("Google", "Amazon", ...).
 *
 * Run:  java -p . -m money/money.AmexMatchReport [all.csv] [plaid-transactions.csv]
 *
 * Writes build/reports/amex-match-report.csv with one row per Simplifi Amex
 * transaction: whether it matched, and if so, what Plaid's richer fields say.
 */
public final class AmexMatchReport {

    /** How many days apart a Simplifi row and a Plaid row can be and still count as the same charge. */
    private static final int DATE_TOLERANCE_DAYS = 2;
    /** How close two amounts need to be, in dollars, to count as the same charge. */
    private static final double AMOUNT_TOLERANCE = 0.005;
    /** Simplifi Account values to treat as this Amex card (covers the duplicate-account naming we found). */
    private static final Set<String> AMEX_ACCOUNTS = Set.of(
            "American Express\u00ae Traditional Gold", "Traditional Gold Card");

    record SimplifiRow(LocalDate date, String account, String payee, String category, double amount) {}
    record PlaidRow(LocalDate date, String name, String merchantName, String originalDescription, double amount) {}
    record MatchResult(PlaidRow row, boolean ambiguous) {}

    public static void main(String[] args) {
        Path simplifiCsv = Path.of(args.length > 0 ? args[0] : "all.csv");
        Path plaidCsv = Path.of(args.length > 1 ? args[1] : "plaid-transactions.csv");
        Path output = Path.of("build", "reports", "amex-match-report.csv");

        List<SimplifiRow> amexRows = loadSimplifiAmexRows(simplifiCsv);
        List<PlaidRow> plaidRows = loadPlaidRows(plaidCsv);

        List<String> lines = new ArrayList<>();
        lines.add("Date,Payee,Category,Amount,Matched,MatchStatus,PlaidName,PlaidMerchant,PlaidOriginalDescription,LooksMoreSpecific");

        boolean[] usedPlaid = new boolean[plaidRows.size()];
        int matched = 0;
        int ambiguous = 0;
        int moreSpecific = 0;
        for (SimplifiRow s : amexRows) {
            MatchResult result = findBestMatch(s, plaidRows, usedPlaid);
            PlaidRow best = result == null ? null : result.row();
            boolean isMatch = best != null;
            boolean isAmbiguous = isMatch && result.ambiguous();
            if (isMatch) matched++;
            if (isAmbiguous) ambiguous++;

            String plaidName = isMatch ? best.name() : "";
            String plaidMerchant = isMatch ? best.merchantName() : "";
            String plaidOriginal = isMatch ? best.originalDescription() : "";
            boolean specific = isMatch && looksMoreSpecific(
                    s.payee(), plaidOriginal, plaidMerchant, plaidName);
            if (specific) moreSpecific++;

            lines.add(String.join(",",
                    csv(String.valueOf(s.date())),
                    csv(s.payee()),
                    csv(s.category()),
                    csv(String.format(Locale.US, "%.2f", s.amount())),
                    csv(isMatch ? "yes" : "no"),
                    csv(isMatch ? (isAmbiguous ? "ambiguous" : "unique") : "unmatched"),
                    csv(plaidName),
                    csv(plaidMerchant),
                    csv(plaidOriginal),
                    csv(specific ? "yes" : "")));
        }

        writeReport(output, lines);
        System.out.printf(Locale.US,
                "Simplifi Amex rows: %d, matched to Plaid: %d (%.0f%%), ambiguous: %d, with a more specific description: %d%n",
                amexRows.size(), matched,
                amexRows.isEmpty() ? 0.0 : 100.0 * matched / amexRows.size(),
                ambiguous, moreSpecific);
        System.out.println("Wrote " + output.toAbsolutePath());
    }

    /**
     * Picks one unused Plaid row. Simplifi records spending as negative while
     * Plaid records it as positive, so matching transactions have opposite
     * signs. Merchant-related candidates outrank unrelated candidates, then
     * the closest date wins. Equal best candidates are marked ambiguous.
     */
    private static MatchResult findBestMatch(
            SimplifiRow s, List<PlaidRow> plaidRows, boolean[] usedPlaid) {
        int bestIndex = -1;
        int bestTextRank = Integer.MAX_VALUE;
        long bestDays = Long.MAX_VALUE;
        int tiedBest = 0;

        for (int i = 0; i < plaidRows.size(); i++) {
            if (usedPlaid[i]) continue;
            PlaidRow p = plaidRows.get(i);
            if (Math.abs(Math.abs(p.amount()) - Math.abs(s.amount())) > AMOUNT_TOLERANCE) continue;
            if (!sameTransactionDirection(s.amount(), p.amount())) continue;

            long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(s.date(), p.date()));
            if (days > DATE_TOLERANCE_DAYS) continue;
            int textRank = merchantRelated(s.payee(), p) ? 0 : 1;

            if (textRank < bestTextRank || (textRank == bestTextRank && days < bestDays)) {
                bestIndex = i;
                bestTextRank = textRank;
                bestDays = days;
                tiedBest = 1;
            } else if (textRank == bestTextRank && days == bestDays) {
                tiedBest++;
            }
        }

        if (bestIndex < 0) return null;
        usedPlaid[bestIndex] = true;
        return new MatchResult(plaidRows.get(bestIndex), tiedBest > 1);
    }

    private static boolean sameTransactionDirection(double simplifiAmount, double plaidAmount) {
        if (simplifiAmount == 0 || plaidAmount == 0) {
            return simplifiAmount == 0 && plaidAmount == 0;
        }
        return Math.signum(simplifiAmount) == -Math.signum(plaidAmount);
    }

    private static boolean merchantRelated(String payee, PlaidRow plaid) {
        String token = CategorizationAudit.cleanPayee(payee);
        if (token.isEmpty()) return false;
        return token.equals(CategorizationAudit.cleanPayee(plaid.name()))
                || token.equals(CategorizationAudit.cleanPayee(plaid.merchantName()))
                || token.equals(CategorizationAudit.cleanPayee(plaid.originalDescription()));
    }

    /** True if any Plaid text says something Simplifi's payee doesn't already say. */
    private static boolean looksMoreSpecific(
            String payee, String originalDescription, String merchantName, String plaidName) {
        String payeeNorm = payee.strip().toLowerCase(Locale.ROOT);
        for (String richer : List.of(originalDescription, merchantName, plaidName)) {
            if (richer.isBlank()) continue;
            String richerNorm = richer.strip().toLowerCase(Locale.ROOT);
            if (!richerNorm.equals(payeeNorm) && richerNorm.length() > payeeNorm.length()) {
                return true;
            }
        }
        return false;
    }

    // ---- Simplifi CSV loading ----------------------------------------------

    private static List<SimplifiRow> loadSimplifiAmexRows(Path csv) {
        List<String> lines = readAllLines(csv);
        if (lines.isEmpty()) return List.of();

        List<String> header = parseCsvLine(lines.get(0));
        int dateCol = indexOfHeader(header, "Date");
        int accountCol = indexOfHeader(header, "Account");
        int payeeCol = indexOfHeader(header, "Payee");
        int catCol = indexOfHeader(header, "Category");
        int amtCol = indexOfHeader(header, "Amount");
        int exclCol = indexOfHeader(header, "Exclusion");

        List<SimplifiRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) continue;
            List<String> fields = parseCsvLine(raw);
            if (accountCol < 0 || accountCol >= fields.size()) continue;
            String account = fields.get(accountCol).strip();
            if (!AMEX_ACCOUNTS.contains(account)) continue;
            if (exclCol >= 0 && exclCol < fields.size()
                    && fields.get(exclCol).strip().equalsIgnoreCase("yes")) continue;

            LocalDate date = (dateCol >= 0 && dateCol < fields.size())
                    ? parseDate(fields.get(dateCol)) : null;
            if (date == null) continue;
            double amount = (amtCol >= 0 && amtCol < fields.size())
                    ? parseAmount(fields.get(amtCol)) : 0;
            String payee = (payeeCol >= 0 && payeeCol < fields.size()) ? fields.get(payeeCol).strip() : "";
            String category = (catCol >= 0 && catCol < fields.size()) ? fields.get(catCol).strip() : "";

            rows.add(new SimplifiRow(date, account, payee, category, amount));
        }
        return rows;
    }

    // ---- Plaid CSV loading (from PullTransactions's output) ---------------

    private static List<PlaidRow> loadPlaidRows(Path csv) {
        List<String> lines = readAllLines(csv);
        if (lines.isEmpty()) return List.of();

        List<String> header = parseCsvLine(lines.get(0));
        int dateCol = indexOfHeader(header, "Date");
        int nameCol = indexOfHeader(header, "Name");
        int merchantCol = indexOfHeader(header, "MerchantName");
        int origCol = indexOfHeader(header, "OriginalDescription");
        int amtCol = indexOfHeader(header, "Amount");

        List<PlaidRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) continue;
            List<String> fields = parseCsvLine(raw);
            LocalDate date = (dateCol >= 0 && dateCol < fields.size())
                    ? parseIsoDate(fields.get(dateCol)) : null;
            if (date == null) continue;
            double amount = (amtCol >= 0 && amtCol < fields.size())
                    ? parseAmount(fields.get(amtCol)) : 0;
            String name = field(fields, nameCol);
            String merchant = field(fields, merchantCol);
            String original = field(fields, origCol);

            rows.add(new PlaidRow(date, name, merchant, original, amount));
        }
        return rows;
    }

    private static String field(List<String> fields, int col) {
        return (col >= 0 && col < fields.size()) ? fields.get(col).strip() : "";
    }

    private static LocalDate parseIsoDate(String s) {
        try {
            return LocalDate.parse(s.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- Shared helpers -----------------------------------------------------

    private static List<String> readAllLines(Path csv) {
        try {
            return Files.readAllLines(csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + csv, e);
        }
    }

    private static final String[] DATE_PATTERNS = {
        "MMM d, yyyy", "MMMM d, yyyy",
        "M/d/yyyy", "MM/dd/yyyy", "M/d/yy", "MM/dd/yy",
        "yyyy/M/d", "M-d-yyyy", "MM-dd-yyyy", "d-MMM-yyyy", "d MMM yyyy"
    };

    private static LocalDate parseDate(String s) {
        String t = s.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).strip();
        }
        if (t.isEmpty()) return null;
        try {
            return LocalDate.parse(t);
        } catch (RuntimeException ignore) { /* fall through */ }
        for (String pat : DATE_PATTERNS) {
            try {
                return LocalDate.parse(t, DateTimeFormatter.ofPattern(pat, Locale.US));
            } catch (RuntimeException ignore) { /* try next */ }
        }
        return null;
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
        if (t.startsWith("-")) { negative = true; t = t.substring(1); }
        if (t.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(t);
            return negative ? -v : v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int indexOfHeader(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).strip().equalsIgnoreCase(name)) return i;
        }
        return -1;
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

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static void writeReport(Path output, List<String> lines) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(output, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + output, e);
        }
    }
}