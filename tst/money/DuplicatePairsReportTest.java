package money;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class DuplicatePairsReportTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("duplicate-pairs-test-");
        Path report = directory.resolve("nested/duplicate-pairs.csv");
        List<SpendingPlot.Row> rows = new ArrayList<>();
        for (int day = 1; day <= 20; day++) {
            String exclusion = day == 1 ? "Yes" : "";
            rows.add(row("A Kept, Card", "Shop \"One\"", -day, day, exclusion));
            rows.add(row("Z Mirror Card", "Shop \"One\"", -day, day, "No"));
        }

        int count = SpendingPlot.writeDuplicatePairs(rows, report);
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        require(count == 20, "Expected 20 pairs, got " + count);
        require(lines.size() == 21, "Expected header plus 20 rows, got " + lines.size());
        require(lines.get(0).equals("Date,Payee,Amount,Account 1,Account 1 Exclusion,Account 2,Account 2 Exclusion"),
                "Unexpected header: " + lines.get(0));
        require(lines.get(1).contains("\"Shop \"\"One\"\"\""), "Payee was not CSV escaped: " + lines.get(1));
        require(lines.get(1).contains("\"A Kept, Card\""), "Account was not CSV escaped: " + lines.get(1));
        require(lines.get(1).endsWith(",Z Mirror Card,No,\"A Kept, Card\",Yes"),
                "Exclusion flags missing: " + lines.get(1));
        require(!Files.readString(report, StandardCharsets.UTF_8).contains("\r"), "Report must use LF line endings");
        require(MirrorDuplicates.remove(rows).size() == 20, "Pair extraction changed mirror removal behavior");

        Path emptyReport = directory.resolve("empty.csv");
        require(SpendingPlot.writeDuplicatePairs(List.of(), emptyReport) == 0, "Empty input reported pairs");
        require(Files.readAllLines(emptyReport, StandardCharsets.UTF_8).size() == 1,
                "Empty report should contain only its header");

        Path input = directory.resolve("input.csv");
        StringBuilder csvBuilder = new StringBuilder("Date,Account,Payee,Category,Notes,Exclusion,Amount\n");
        for (int day = 1; day <= 20; day++) {
            csvBuilder.append("10/").append(day).append("/2026,A Kept Card,Shop,Shopping,,Yes,-")
                    .append(day).append(".00\n");
            csvBuilder.append("10/").append(day).append("/2026,Z Mirror Card,Shop,Shopping,,No,-")
                    .append(day).append(".00\n");
        }
        String csv = csvBuilder.toString();
        Files.writeString(input, csv, StandardCharsets.UTF_8);
        Path loadReport = directory.resolve("load.csv");
        SpendingPlot.loadAllTransactions(input, loadReport);
        require(Files.readString(input, StandardCharsets.UTF_8).equals(csv), "Input CSV changed");
        require(Files.readAllLines(loadReport, StandardCharsets.UTF_8).size() == 21,
                "Excluded duplicate rows were not reported before filtering");

        Path emptyInput = directory.resolve("empty-input.csv");
        Files.writeString(emptyInput, "", StandardCharsets.UTF_8);
        Path emptyInputReport = directory.resolve("empty-input-report.csv");
        SpendingPlot.loadAllTransactions(emptyInput, emptyInputReport);
        require(Files.readAllLines(emptyInputReport, StandardCharsets.UTF_8).size() == 1,
                "Empty input should still create a header-only report");
        System.out.println("Duplicate-pair report tests passed");
    }

    private static SpendingPlot.Row row(String account, String payee, double amount, int day, String exclusion) {
        return new SpendingPlot.Row(account, new SpendingPlot.Transaction(
                LocalDate.of(2026, 10, day), "Shopping", payee, "", amount), exclusion);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
