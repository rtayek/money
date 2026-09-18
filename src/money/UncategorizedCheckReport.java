package money;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes a small lookup list for uncategorized paper checks in a Simplifi CSV export. */
public final class UncategorizedCheckReport {

    record CheckRow(LocalDate date, String checkNumber, BigDecimal amount) {}

    private UncategorizedCheckReport() {}

    public static void main(String[] args) {
        Path input = args.length > 0 ? Path.of(args[0]) : defaultInput;
        Path output = args.length > 1 ? Path.of(args[1]) : defaultOutput;
        List<CheckRow> checks = loadChecks(input);
        writeReport(output, checks);
        System.out.printf("Wrote %d uncategorized checks to %s%n",
                checks.size(), output.toAbsolutePath());
    }

    static List<CheckRow> loadChecks(Path csv) {
        List<String> lines;
        try {
            lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + csv, e);
        }
        if (lines.isEmpty()) return List.of();

        List<String> header = parseCsvLine(lines.get(0));
        int dateCol = indexOfHeader(header, "Date");
        int payeeCol = indexOfHeader(header, "Payee");
        int categoryCol = indexOfHeader(header, "Category");
        int exclusionCol = indexOfHeader(header, "Exclusion");
        int amountCol = indexOfHeader(header, "Amount");
        if (dateCol < 0 || payeeCol < 0 || categoryCol < 0 || amountCol < 0) {
            throw new IllegalStateException(
                    "CSV must have Date, Payee, Category, and Amount columns; found: " + header);
        }

        List<CheckRow> checks = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> fields = parseCsvLine(lines.get(i));
            int required = Math.max(Math.max(dateCol, payeeCol),
                    Math.max(categoryCol, amountCol));
            if (fields.size() <= required) continue;
            if (exclusionCol >= 0 && exclusionCol < fields.size()
                    && fields.get(exclusionCol).strip().equalsIgnoreCase("yes")) continue;

            String category = fields.get(categoryCol).strip();
            if (!(category.isEmpty() || category.equalsIgnoreCase("Uncategorized")
                    || category.equalsIgnoreCase("(Uncategorized)"))) continue;

            Matcher matcher = checkPayee.matcher(fields.get(payeeCol).strip());
            if (!matcher.matches()) continue;
            BigDecimal amount = parseAmount(fields.get(amountCol));
            if (amount.signum() >= 0) continue;
            LocalDate date = parseDate(fields.get(dateCol));
            if (date == null) continue;
            checks.add(new CheckRow(date, matcher.group(1), amount.negate()));
        }
        checks.sort(Comparator.comparing(CheckRow::date).reversed()
                .thenComparing(CheckRow::checkNumber));
        return List.copyOf(checks);
    }

    static void writeReport(Path output, List<CheckRow> checks) {
        List<String> lines = new ArrayList<>();
        lines.add("Date,Check Number,Amount");
        for (CheckRow check : checks) {
            lines.add(String.format(Locale.US, "%s,%s,%s",
                    check.date(), check.checkNumber(), check.amount().toPlainString()));
        }
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(output, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + output, e);
        }
    }

    static BigDecimal parseAmount(String value) {
        String text = value.strip();
        boolean parenthesized = text.startsWith("(") && text.endsWith(")");
        if (parenthesized) text = text.substring(1, text.length() - 1);
        text = text.replace("$", "").replace(",", "").strip();
        BigDecimal amount = new BigDecimal(text);
        return parenthesized ? amount.negate() : amount;
    }

    static LocalDate parseDate(String value) {
        String text = value.strip();
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException ignored) {
            // Try the known Simplifi and common US date formats below.
        }
        for (String pattern : datePatterns) {
            try {
                return LocalDate.parse(text,
                        DateTimeFormatter.ofPattern(pattern, Locale.US));
            } catch (RuntimeException ignored) {
                // Try the next format.
            }
        }
        return null;
    }

    static int indexOfHeader(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).strip().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    // ---- fields -------------------------------------------------------

    private static final Path defaultInput = Path.of("all.csv");
    private static final Path defaultOutput =
            Path.of("build", "reports", "uncategorized-checks.csv");
    private static final Pattern checkPayee =
            Pattern.compile("(?i)^check\\s+(\\d+)\\s*$");
    private static final String[] datePatterns = {
        "MMM d, yyyy", "MMMM d, yyyy", "M/d/yyyy", "MM/dd/yyyy",
        "M/d/yy", "MM/dd/yy", "yyyy/M/d", "M-d-yyyy", "MM-dd-yyyy",
        "d-MMM-yyyy", "d MMM yyyy"
    };
}
