package money;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Throwaway: prints the standard deviation of monthly totals per category. */
public final class StdDevReport {
    public static void main(String[] args) {
        Path csv = Path.of("all.csv");
        List<SpendingPlot.Transaction> txns = SpendingPlot.loadTransactions(csv);

        // full ordered set of months present in the data
        TreeSet<YearMonth> monthSet = new TreeSet<>();
        for (SpendingPlot.Transaction t : txns)
            if (t.date() != null) monthSet.add(YearMonth.from(t.date()));
        List<YearMonth> months = new ArrayList<>(monthSet);
        int n = months.size();
        Map<YearMonth, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) idx.put(months.get(i), i);

        // per-category per-month totals (zeros for months with no spending)
        Map<String, double[]> byCat = new LinkedHashMap<>();
        for (SpendingPlot.Transaction t : txns) {
            if (t.date() == null) continue;
            byCat.computeIfAbsent(t.category(), k -> new double[n])
                 [idx.get(YearMonth.from(t.date()))] += t.amount();
        }

        record Row(String cat, double mean, double sd) {}
        List<Row> rows = new ArrayList<>();
        for (var e : byCat.entrySet()) {
            double[] a = e.getValue();
            double mean = 0;
            for (double v : a) mean += v;
            mean /= n;
            double var = 0;
            for (double v : a) var += (v - mean) * (v - mean);
            var /= n;                       // population std dev over all months
            rows.add(new Row(e.getKey(), mean, Math.sqrt(var)));
        }
        rows.sort(Comparator.comparingDouble(Row::sd).reversed());

        System.out.printf(Locale.US, "%d months: %s .. %s%n",
                n, months.get(0), months.get(n - 1));
        System.out.printf(Locale.US, "%-34s %12s %12s%n", "Category", "MonthlyMean", "StdDev");
        for (Row r : rows)
            System.out.printf(Locale.US, "%-34s %,12.2f %,12.2f%n", r.cat(), r.mean(), r.sd());
    }
}
