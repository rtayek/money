package money;

import java.time.LocalDate;
import java.util.List;

public final class ReversalPairsTest {
    public static void main(String[] args) {
        var charge = row("Card", "Store", -20, 10);
        check(0, charge, row("Card", "Store Refund", 20, 11));
        check(0, charge, row("card", "STORE - REVERSED", 20, 13));
        check(2, charge, row("Card", "Other Store Refund", 20, 11));
        check(2, charge, row("Card", "Refund", 20, 11));
        check(2, charge, row("Other Card", "Store Refund", 20, 11));
        check(2, charge, row("Card", "Store Refund", 20, 14));
        check(2, charge, row("Card", "Store", 20, 11));
        check(2, charge, row("Card", "Store Refund", 21, 11));
        check(2, row("", "Store", -20, 10), row("", "Store Refund", 20, 11));
        check(3, charge, row("Card", "Store Refund", 20, 11), row("Card", "Store Refund", 20, 12));
        check(3, charge, row("Card", "Store", -20, 11), row("Card", "Store Refund", 20, 12));
        check(1, charge, row("Card", "Store Refund", 20, 11), row("Card", "Other Refund", 20, 11));
        System.out.println("Reversal pair tests passed");
    }

    private static SpendingPlot.Row row(String account, String payee, double amount, int day) {
        return new SpendingPlot.Row(account, new SpendingPlot.Transaction(
                LocalDate.of(2026, 10, day), "Shopping", payee, "", amount), "");
    }

    private static void check(int remaining, SpendingPlot.Row... rows) {
        var result = SpendingPlot.withoutReversalPairs(List.of(rows));
        if (result.size() != remaining) {
            throw new AssertionError("Expected " + remaining + " remaining rows, got " + result);
        }
    }
}
