package money;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * Reads a Quicken Simplifi transaction CSV export and plots total spending by
 * category as a horizontal bar chart. No external dependencies (pure Swing).
 *
 * Run:  java --enable-preview -p . -m money/money.SpendingPlot [file.csv]
 * or from Eclipse just run this class; if no file is given a chooser opens.
 */
public final class SpendingPlot {

    /** One spending category and its total (a positive dollar amount). */
    record CategoryTotal(String category, double total) {}

    /** A single spending transaction (amount is a positive outflow). */
    record Transaction(LocalDate date, String category, String payee, double amount) {}

    /** True if a category should be excluded from the deviation pane. */
    private static boolean isDeviationExcluded(String category) {
        for (String prefix : DEVIATION_EXCLUDED)
            if (category.equals(prefix) || category.startsWith(prefix + ":")) return true;
        return false;
    }

    /** Uses ./all.csv if present, otherwise opens a file chooser. */
    private static Path chooseOrDefault() {
        Path defaultCsv = Path.of("all.csv");
        return Files.exists(defaultCsv) ? defaultCsv : chooseFile();
    }

    // ---- CSV loading ------------------------------------------------------

    /**
     * Parses every row of the CSV, dropping only what Simplifi itself flagged
     * as excluded (account transfers, card payments, etc. via the "Exclusion"
     * column). Every month and category is kept, and the amount keeps its
     * original CSV sign (negative = money out). This is the single read of
     * the file; {@link #toSpendingTransactions}, {@link #findUncategorizedChecks},
     * and {@link #printUncategorized} each filter this list for their own needs.
     */
    static List<Transaction> loadAllTransactions(Path csv) {
        List<String> lines;
        try {
            lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + csv, e);
        }
        if (lines.isEmpty()) return List.of();

        List<String> header = parseCsvLine(lines.get(0));
        int dateCol = indexOfHeader(header, "Date");
        int catCol = indexOfHeader(header, "Category");
        int amtCol = indexOfHeader(header, "Amount");
        int exclCol = indexOfHeader(header, "Exclusion");
        int payeeCol = indexOfHeader(header, "Payee");
        if (catCol < 0 || amtCol < 0) {
            throw new IllegalStateException(
                    "CSV must have 'Category' and 'Amount' columns; found: " + header);
        }

        List<Transaction> txns = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) continue;
            List<String> fields = parseCsvLine(raw);
            if (amtCol >= fields.size() || catCol >= fields.size()) continue;

            // Reject anything Simplifi has flagged as excluded (transfers, etc.).
            if (exclCol >= 0 && exclCol < fields.size()
                    && fields.get(exclCol).strip().equalsIgnoreCase("yes")) continue;

            double amount = parseAmount(fields.get(amtCol));
            String category = fields.get(catCol).strip();
            if (category.isEmpty()) category = "(Uncategorized)";
            LocalDate date = (dateCol >= 0 && dateCol < fields.size())
                    ? parseDate(fields.get(dateCol)) : null;
            String payee = (payeeCol >= 0 && payeeCol < fields.size())
                    ? fields.get(payeeCol).strip() : "";

            txns.add(new Transaction(date, category, payee, amount));
        }
        return txns;
    }

    /**
     * Narrows an unfiltered transaction list down to what the charts show:
     * outflows only (amount flipped to a positive spending magnitude), the
     * generic income/transfer categories dropped, anything before
     * {@link #START_DATE} dropped, and the current (still-accumulating) month
     * dropped since it's only partial.
     */
    static List<Transaction> toSpendingTransactions(List<Transaction> all) {
        List<Transaction> out = new ArrayList<>();
        for (Transaction t : all) {
            if (t.amount() >= 0) continue; // only outflows count as spending
            if (EXCLUDED_CATEGORIES.contains(t.category())) continue;
            if (START_DATE != null && t.date() != null && t.date().isBefore(START_DATE)) continue;
            if (t.date() != null && YearMonth.from(t.date()).equals(YearMonth.now())) continue;
            out.add(new Transaction(t.date(), t.category(), t.payee(), -t.amount()));
        }
        return out;
    }

    /** True if a transaction has no meaningful category assigned. */
    private static boolean isUncategorized(Transaction t) {
        String category = t.category() == null ? "" : t.category().strip();
        return category.isEmpty()
                || category.equalsIgnoreCase("Uncategorized")
                || category.equalsIgnoreCase("(Uncategorized)");
    }

    /** One uncategorized paper check: its date, check number, and paid-out amount. */
    record CheckEntry(LocalDate date, String checkNumber, double amount) {}

    /**
     * Finds uncategorized paper checks in {@code all} -- every month, not just
     * ones {@link #toSpendingTransactions} would keep, so a check written this
     * month still shows up here in time to categorize it.
     */
    static List<CheckEntry> findUncategorizedChecks(List<Transaction> all) {
        List<CheckEntry> checks = new ArrayList<>();
        for (Transaction t : all) {
            if (!isUncategorized(t) || t.payee() == null) continue;
            Matcher m = CHECK_PAYEE.matcher(t.payee().strip());
            if (!m.matches()) continue;
            if (t.amount() >= 0) continue; // only paid-out checks
            checks.add(new CheckEntry(t.date(), m.group(1), -t.amount()));
        }
        // Undated checks (unparseable/missing date) still count; sort them last.
        checks.sort(Comparator.comparing(CheckEntry::date,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CheckEntry::checkNumber));
        return checks;
    }

    /** Prints every uncategorized paper check to stdout, undated ones included. */
    static void printUncategorizedChecks(List<CheckEntry> checks) {
        if (checks.isEmpty()) {
            System.out.println("No uncategorized checks.");
            return;
        }
        System.out.printf(Locale.US, "%-12s %-10s %12s%n", "Date", "Check", "Amount");
        double total = 0;
        for (CheckEntry c : checks) {
            total += c.amount();
            System.out.printf(Locale.US, "%-12s %-10s %,12.2f%n",
                    c.date() == null ? "(no date)" : c.date(), c.checkNumber(), c.amount());
        }
        System.out.printf(Locale.US, "%-12s %-10s %,12.2f%n", "", "Total", total);
    }

    /** Writes the uncategorized-check list to a CSV report, creating parent directories as needed. */
    static void writeUncategorizedChecks(List<CheckEntry> checks, Path output) {
        List<String> lines = new ArrayList<>();
        lines.add("Date,Check Number,Amount");
        for (CheckEntry c : checks) {
            lines.add(String.format(Locale.US, "%s,%s,%.2f",
                    c.date() == null ? "" : c.date(), c.checkNumber(), c.amount()));
        }
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(output, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + output, e);
        }
    }

    /**
     * Prints every uncategorized transaction that is not a paper check to
     * stdout (paper checks are covered by {@link #findUncategorizedChecks}).
     * Reads from the unfiltered list so the current month is included.
     */
    static void printUncategorized(List<Transaction> all) {
        List<Transaction> uncategorized = new ArrayList<>();
        for (Transaction t : all) {
            if (!isUncategorized(t)) continue;
            if (t.amount() >= 0) continue; // only outflows
            if (t.payee() != null && CHECK_PAYEE.matcher(t.payee().strip()).matches()) continue;
            uncategorized.add(t);
        }
        uncategorized.sort(Comparator.comparing(Transaction::date,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        if (uncategorized.isEmpty()) {
            System.out.println("No uncategorized transactions.");
            return;
        }
        System.out.printf(Locale.US, "%-12s %12s  %s%n", "Date", "Amount", "Payee");
        double total = 0;
        for (Transaction t : uncategorized) {
            double amount = -t.amount();
            total += amount;
            System.out.printf(Locale.US, "%-12s %,12.2f  %s%n",
                    t.date(), amount, t.payee());
        }
        System.out.printf(Locale.US, "%-12s %,12.2f%n", "Total", total);
    }

    /** Aggregates transactions into per-category totals, sorted largest first. */
    static List<CategoryTotal> categoryTotals(List<Transaction> transactions) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (Transaction t : transactions) totals.merge(t.category(), t.amount(), Double::sum);
        List<CategoryTotal> out = new ArrayList<>();
        totals.forEach((k, v) -> out.add(new CategoryTotal(k, v)));
        out.sort(Comparator.comparingDouble(CategoryTotal::total).reversed());
        return out;
    }

    /**
     * Population standard deviation of each category's monthly spending, taken
     * over every month present in the data (months with no spending count as 0).
     * Returned as {@link CategoryTotal}s (total = std dev), sorted largest first.
     */
    static List<CategoryTotal> categoryMonthlyStdDevs(List<Transaction> transactions) {
        TreeSet<YearMonth> monthSet = new TreeSet<>();
        for (Transaction t : transactions)
            if (t.date() != null) monthSet.add(YearMonth.from(t.date()));
        List<YearMonth> months = new ArrayList<>(monthSet);
        int n = months.size();
        if (n == 0) return List.of();

        Map<YearMonth, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) idx.put(months.get(i), i);

        Map<String, double[]> byCat = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            if (t.date() == null) continue;
            byCat.computeIfAbsent(t.category(), _ -> new double[n])
                 [idx.get(YearMonth.from(t.date()))] += t.amount();
        }

        List<CategoryTotal> out = new ArrayList<>();
        byCat.forEach((cat, a) -> {
            double mean = 0;
            for (double v : a) mean += v;
            mean /= n;
            double var = 0;
            for (double v : a) var += (v - mean) * (v - mean);
            out.add(new CategoryTotal(cat, Math.sqrt(var / n)));
        });
        out.sort(Comparator.comparingDouble(CategoryTotal::total).reversed());
        return out;
    }

    /** Parses a date in a variety of common formats; null if unparseable. */
    static LocalDate parseDate(String s) {
        String t = s.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).strip(); // strip stray quotes
        }
        if (t.isEmpty()) return null;
        try {
            return LocalDate.parse(t); // ISO-8601 yyyy-MM-dd
        } catch (Exception ignore) { /* fall through */ }
        for (String pat : DATE_PATTERNS) {
            try {
                return LocalDate.parse(t, DateTimeFormatter.ofPattern(pat, Locale.US));
            } catch (Exception ignore) { /* try next */ }
        }
        return null;
    }

    /** Case-insensitive header lookup that tolerates surrounding whitespace. */
    private static int indexOfHeader(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).strip().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** Parses a dollar amount, tolerating $, commas, and parenthesized negatives. */
    static double parseAmount(String s) {
        String t = s.strip();
        if (t.isEmpty()) return 0;
        boolean negative = false;
        if (t.startsWith("(") && t.endsWith(")")) { // (12.34) == -12.34
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

    /** Minimal RFC-4180 CSV line parser: handles quotes and escaped "" quotes. */
    static List<String> parseCsvLine(String line) {
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

    // ---- UI -------------------------------------------------------------

    private static Path chooseFile() {
        JFileChooser fc = new JFileChooser(Path.of("").toAbsolutePath().toFile());
        fc.setDialogTitle("Open Simplifi CSV export");
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        int r = fc.showOpenDialog(null);
        return (r == JFileChooser.APPROVE_OPTION) ? fc.getSelectedFile().toPath() : null;
    }

    private static void showCharts(String title, List<Transaction> transactions,
                                   List<CategoryTotal> totals) {
        JFrame frame = new JFrame("Spending — " + title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("By Category", new JScrollPane(new BarChartPanel(totals)));
        tabs.addTab("Over Time", new TimeSeriesPanel(transactions, totals));
        tabs.addTab("Std Dev", new StdDevPanel(categoryMonthlyStdDevs(transactions)));
        // Two deviation panes: after dropping excluded prefixes and anything
        // below the noise floor, split the survivors at their median std dev --
        // top half in "High", bottom half in "Low".
        List<String> ranked = new ArrayList<>();          // sorted high std dev -> low
        for (CategoryTotal sd : categoryMonthlyStdDevs(transactions))
            if (sd.total() >= DEVIATION_MIN_STDDEV && !isDeviationExcluded(sd.category()))
                ranked.add(sd.category());
        int mid = (ranked.size() + 1) / 2;                // high half keeps the odd one
        tabs.addTab("High Deviation", deviationPanel(transactions,
                new HashSet<>(ranked.subList(0, mid))));
        tabs.addTab("Low Deviation", deviationPanel(transactions,
                new HashSet<>(ranked.subList(mid, ranked.size()))));

        // Fifth pane: total monthly spending as one series, plotted as its
        // dollar deviation from the overall monthly mean.
        List<Transaction> totalTxns = new ArrayList<>();
        double grand = 0;
        for (Transaction t : transactions) {
            totalTxns.add(new Transaction(t.date(), "Total", t.payee(), t.amount()));
            grand += t.amount();
        }
        tabs.addTab("Total Deviation", new TimeSeriesPanel(totalTxns,
                List.of(new CategoryTotal("Total", grand)), true));

        frame.add(tabs, BorderLayout.CENTER);
        // Cap to the usable screen area (excludes the Windows taskbar) so the
        // bottom of the plots -- the date axis -- is never pushed off screen.
        Rectangle scr = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int wantH = Math.max(500, 60 + totals.size() * 34);
        frame.setSize(Math.min(960, scr.width), Math.min(wantH, scr.height));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        TimeSeriesPanel.closeAfter(frame, 5);   // auto-close the window after 5 minutes
    }

    /**
     * Builds a deviation-from-mean chart limited to the given categories.
     */
    private static TimeSeriesPanel deviationPanel(List<Transaction> transactions,
                                                  Set<String> keep) {
        List<Transaction> devTxns = new ArrayList<>();
        for (Transaction t : transactions)
            if (keep.contains(t.category())) devTxns.add(t);
        return new TimeSeriesPanel(devTxns, categoryTotals(devTxns), true);
    }

    private static Color seriesColor(int i) {
        float hue = (i * 0.61803399f) % 1f; // golden-ratio hue spread
        return Color.getHSBColor(hue, 0.60f, 0.80f);
    }

    /** Horizontal bar chart drawn with Java2D. */
    @SuppressWarnings("serial")
    static final class BarChartPanel extends JPanel {
        private final List<CategoryTotal> data;
        private final double max;
        private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);

        BarChartPanel(List<CategoryTotal> data) {
            this.data = data;
            this.max = data.stream().mapToDouble(CategoryTotal::total).max().orElse(1);
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(900, 60 + data.size() * 34));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int top = 20, bottom = 20;
            int labelW = 220;          // room for category names on the left
            int valueW = 110;          // room for dollar amounts on the right
            int barX = labelW + 10;
            int barMaxW = Math.max(50, w - barX - valueW - 20);
            int rowH = Math.max(18, (h - top - bottom) / Math.max(1, data.size()));
            int barH = Math.min(24, rowH - 8);

            Font base = getFont().deriveFont(12f);
            g.setFont(base);

            for (int i = 0; i < data.size(); i++) {
                CategoryTotal ct = data.get(i);
                int y = top + i * rowH;
                int barW = (int) Math.round(barMaxW * (ct.total() / max));

                g.setColor(colorFor(i));
                g.fillRoundRect(barX, y, Math.max(1, barW), barH, 6, 6);

                // category label (right-aligned into the left gutter)
                g.setColor(Color.DARK_GRAY);
                drawRightAligned(g, ct.category(), labelW, y + barH - 4);

                // value label to the right of the bar
                String val = money.format(ct.total());
                g.drawString(val, barX + barW + 8, y + barH - 4);
            }

            // baseline
            g.setColor(new Color(0xCC, 0xCC, 0xCC));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(barX, top - 4, barX, top + data.size() * rowH);
        }

        private static void drawRightAligned(Graphics2D g, String s, int rightEdge, int y) {
            Rectangle2D b = g.getFontMetrics().getStringBounds(s, g);
            int x = (int) (rightEdge - b.getWidth());
            g.drawString(s, Math.max(4, x), y);
        }

        private static Color colorFor(int i) {
            return seriesColor(i);
        }
    }

    /**
     * Multi-line chart of monthly spending, one line per category. Categories
     * beyond the top {@link #MAX_SERIES} (by total) are combined into "Other".
     * Transactions with an unparseable/missing date are skipped here.
     */
    @SuppressWarnings("serial")
    static final class TimeSeriesPanel extends JPanel {

        static Timer closeAfter(Window window, int minutes) {
            Timer timer = new Timer(minutes * 60 * 1000, _ -> {
                window.dispose();

                // Use this only if closing the window must terminate the entire app:
                // System.exit(0);
            });

            timer.setRepeats(false);
            timer.start();
            return timer;
        }
        
        TimeSeriesPanel(List<Transaction> txns, List<CategoryTotal> totals) {
            this(txns, totals, false);
        }

        TimeSeriesPanel(List<Transaction> txns, List<CategoryTotal> totals, boolean deviation) {
            this.deviation = deviation;
            // Which categories get their own line; everything else -> "Other".
            List<String> top = new ArrayList<>();
            for (int i = 0; i < totals.size() && i < MAX_SERIES; i++) {
                top.add(totals.get(i).category());
            }
            boolean hasOther = totals.size() > MAX_SERIES;

            TreeSet<YearMonth> monthSet = new TreeSet<>();
            for (Transaction t : txns) {
                if (t.date() != null) monthSet.add(YearMonth.from(t.date()));
            }
            this.months = new ArrayList<>(monthSet);

            Map<String, Integer> idx = new LinkedHashMap<>();
            for (int i = 0; i < months.size(); i++) idx.put(months.get(i).toString(), i);

            this.series = new ArrayList<>(top);
            if (hasOther) series.add("Other");

            this.values = new LinkedHashMap<>();
            for (String s : series) values.put(s, new double[months.size()]);

            for (Transaction t : txns) {
                if (t.date() == null) continue;
                String key = top.contains(t.category()) ? t.category()
                        : (hasOther ? "Other" : t.category());
                double[] arr = values.get(key);
                if (arr == null) continue;
                arr[idx.get(YearMonth.from(t.date()).toString())] += t.amount();
            }

            // In deviation mode each series is re-expressed as (month - its mean).
            if (deviation) {
                for (double[] arr : values.values()) {
                    double mean = 0;
                    for (double v : arr) mean += v;
                    mean /= arr.length;
                    for (int i = 0; i < arr.length; i++) arr[i] -= mean;
                }
            }

            recomputeRange();

            this.txns = txns;
            this.hasOther = series.contains("Other");
            this.topSet = new HashSet<>(series);
            this.topSet.remove("Other");

            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(960, 520));

            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    for (int i = 0; i < legendHit.size(); i++) {
                        if (!legendHit.get(i).contains(e.getPoint())) continue;
                        if (SwingUtilities.isRightMouseButton(e)) {
                            removeSeries(i);            // right-click a legend label: hide it
                        } else {
                            selSeries = (selSeries == i) ? -1 : i;
                            selMonth = -1;              // clear any point drill-down
                            pressing = (selSeries >= 0);
                            repaint();
                        }
                        return;
                    }
                    if (!SwingUtilities.isRightMouseButton(e)) {
                        pickPoint(e.getX(), e.getY());  // otherwise treat as a point click
                    }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (pressing) { pressing = false; repaint(); }
                }
            });
        }

        /** Removes one category's line from this chart (right-click on its legend). */
        private void removeSeries(int i) {
            if (i < 0 || i >= series.size()) return;
            String removed = series.remove(i);
            values.remove(removed);
            topSet.remove(removed);
            if (selSeries == i) selSeries = -1;
            else if (selSeries > i) selSeries--;
            selMonth = -1;
            recomputeRange();               // rescale the y-axis to the remaining lines
            repaint();
        }

        /** Recomputes the {@link #max}/{@link #min} data range over the current series. */
        private void recomputeRange() {
            double hi = 0, lo = 0;
            for (double[] arr : values.values())
                for (double v : arr) { hi = Math.max(hi, v); lo = Math.min(lo, v); }
            this.max = (hi == 0) ? 1 : hi;
            this.min = lo;
        }

        /** Selects the plotted point nearest the click and lists its transactions. */
        private void pickPoint(int mx, int my) {
            if (ptX == null) return;
            int bestS = -1, bestI = -1, bestD = 12 * 12; // accept within ~12px
            for (int s = 0; s < ptX.length; s++) {
                for (int i = 0; i < ptX[s].length; i++) {
                    int dx = mx - ptX[s][i], dy = my - ptY[s][i];
                    int d = dx * dx + dy * dy;
                    if (d <= bestD) { bestD = d; bestS = s; bestI = i; }
                }
            }
            if (bestS < 0) return;
            selSeries = bestS; selMonth = bestI;
            repaint();
            showTransactions(bestS, bestI);
        }

        /** Pops the transactions behind the selected (series, month) point. */
        private void showTransactions(int s, int i) {
            String name = series.get(s);
            YearMonth ym = months.get(i);
            List<Transaction> hits = new ArrayList<>();
            for (Transaction t : txns) {
                if (t.date() == null || !YearMonth.from(t.date()).equals(ym)) continue;
                String key = topSet.contains(t.category()) ? t.category()
                        : (hasOther ? "Other" : t.category());
                if (key.equals(name)) hits.add(t);
            }
            hits.sort(Comparator.comparing(Transaction::date));
            DateTimeFormatter df = DateTimeFormatter.ofPattern("MMM d", Locale.US);
            StringBuilder sb = new StringBuilder();
            double total = 0;
            for (Transaction t : hits) {
                total += t.amount();
                sb.append(String.format(Locale.US, "%-7s %11s  %-22s %s%n",
                        t.date().format(df), money.format(t.amount()),
                        t.category(), t.payee()));
            }
            if (hits.isEmpty()) sb.append("(no transactions)");
            String header = String.format(Locale.US, "%s  -  %s   (%d txns, %s)",
                    name, ym.format(xFmt), hits.size(), money.format(total));
            JTextArea area = new JTextArea(sb.toString(),
                    Math.min(24, Math.max(6, hits.size() + 1)), 64);
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            JOptionPane.showMessageDialog(this, new JScrollPane(area), header,
                    JOptionPane.PLAIN_MESSAGE);
        }

        /** A washed-out version of a series color, for the non-selected lines. */
        private static Color fade(Color c) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 45);
        }

        /** Tick step (a whole multiple of $1,000) giving roughly five gridlines. */
        private static double thousandStep(double dataMax) {
            double step = Math.ceil(dataMax / 5.0 / 1000.0) * 1000.0;
            return Math.max(1000.0, step);
        }

        /**
         * A "nice" 1 / 2 / 5 x 10^k tick step giving roughly five gridlines over
         * {@code range} -- used by the deviation panes so small spans aren't
         * squished against zero.
         */
        private static double niceStep(double range) {
            if (range <= 0) return 1;
            double raw = range / 5.0;
            double pow = Math.pow(10, Math.floor(Math.log10(raw)));
            double f = raw / pow;
            double nice = (f <= 1) ? 1 : (f <= 2) ? 2 : (f <= 5) ? 5 : 10;
            return nice * pow;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont().deriveFont(11f));

            int w = getWidth(), h = getHeight();
            int left = 70, right = 180, top = 20, bottom = 50;
            int plotW = w - left - right;
            int plotH = h - top - bottom;

            if (months.isEmpty()) {
                g.setColor(Color.GRAY);
                g.drawString("No dated transactions to plot.", left, top + 20);
                return;
            }

            // y grid: deviation mode fills the plot with the exact [min, max]
            // range and draws gridlines only at nice steps inside it; the plain
            // chart runs 0..max rounded up to a nice step.
            double step, axisMin, axisMax;
            int loTick, hiTick;
            if (deviation) {
                axisMin = min;
                axisMax = max;
                if (axisMax <= axisMin) axisMax = axisMin + 1; // guard a flat range
                step = niceStep(axisMax - axisMin);
                loTick = (int) Math.ceil(axisMin / step);      // gridlines within range
                hiTick = (int) Math.floor(axisMax / step);
            } else {
                step = thousandStep(max);
                hiTick = (int) Math.ceil(max / step);
                loTick = 0;
                axisMax = hiTick * step;
                axisMin = 0;
            }
            double span = axisMax - axisMin;
            java.util.function.DoubleUnaryOperator yAt = v ->
                    top + plotH - (int) Math.round(plotH * ((v - axisMin) / span));

            for (int t = loTick; t <= hiTick; t++) {
                double val = t * step;
                int y = (int) yAt.applyAsDouble(val);
                g.setColor(val == 0 ? new Color(0xCC, 0xCC, 0xCC) : new Color(0xEE, 0xEE, 0xEE));
                g.drawLine(left, y, left + plotW, y);
                g.setColor(Color.GRAY);
                String lab = String.format(Locale.US, "$%,d", (long) val);
                drawRightAligned(g, lab, left - 6, y + 4);
            }

            // axes (the horizontal one sits on the zero line)
            int yZero = (int) yAt.applyAsDouble(0);
            g.setColor(new Color(0xAA, 0xAA, 0xAA));
            g.drawLine(left, top, left, top + plotH);
            g.drawLine(left, yZero, left + plotW, yZero);

            // x positions (one slot per month)
            int n = months.size();
            java.util.function.IntUnaryOperator xAt = i ->
                    left + (n == 1 ? plotW / 2 : (int) Math.round(plotW * (i / (double) (n - 1))));

            // x labels (thinned so they don't overlap)
            g.setColor(Color.GRAY);
            int every = Math.max(1, n / 12);
            for (int i = 0; i < n; i++) {
                if (i % every != 0 && i != n - 1) continue;
                String lab = months.get(i).format(xFmt);
                Rectangle2D b = g.getFontMetrics().getStringBounds(lab, g);
                g.drawString(lab, xAt.applyAsInt(i) - (int) (b.getWidth() / 2),
                        top + plotH + 16);
            }

            // one line per series; remember point coords for click hit-testing
            ptX = new int[series.size()][n];
            ptY = new int[series.size()][n];
            for (int s = 0; s < series.size(); s++) {
                boolean sel = (s == selSeries);
                double[] arr = values.get(series.get(s));
                g.setColor(selSeries >= 0 && !sel ? fade(seriesColor(s)) : seriesColor(s));
                g.setStroke(new BasicStroke(sel ? (pressing ? 6f : 4f) : 2f));
                int dot = sel ? (pressing ? 4 : 3) : 2;
                int prevX = 0, prevY = 0;
                for (int i = 0; i < n; i++) {
                    int x = xAt.applyAsInt(i);
                    int y = (int) yAt.applyAsDouble(arr[i]);
                    ptX[s][i] = x; ptY[s][i] = y;
                    if (i > 0) g.drawLine(prevX, prevY, x, y);
                    g.fillOval(x - dot, y - dot, 2 * dot, 2 * dot);
                    prevX = x; prevY = y;
                }
            }

            // ring the selected point
            if (selSeries >= 0 && selMonth >= 0) {
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(2f));
                int x = ptX[selSeries][selMonth], y = ptY[selSeries][selMonth];
                g.drawOval(x - 6, y - 6, 12, 12);
            }

            // legend (click a label to highlight its line; click a point to drill in)
            legendHit.clear();
            int lx = left + plotW + 14;
            int ly = top + 4;
            for (int s = 0; s < series.size(); s++) {
                boolean sel = (s == selSeries);
                if (sel) {                       // highlight the selected series' row
                    Color c = seriesColor(s);
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 55));
                    g.fillRoundRect(lx - 4, ly - 2, right - 20, 16, 6, 6);
                    g.setColor(c);
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawRoundRect(lx - 4, ly - 2, right - 20, 16, 6, 6);
                }
                g.setColor(seriesColor(s));
                g.fillRect(lx, ly, 12, 12);
                g.setColor(sel ? Color.BLACK : Color.DARK_GRAY);
                g.setFont(getFont().deriveFont(sel ? Font.BOLD : Font.PLAIN, 11f));
                g.drawString(series.get(s), lx + 18, ly + 11);
                legendHit.add(new Rectangle(lx, ly, right - 20, 16));
                ly += 20;
            }
            g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        }

        private static void drawRightAligned(Graphics2D g, String s, int rightEdge, int y) {
            Rectangle2D b = g.getFontMetrics().getStringBounds(s, g);
            g.drawString(s, Math.max(2, (int) (rightEdge - b.getWidth())), y);
        }

        // ---- fields -------------------------------------------------------

        private final List<String> series;              // category names, in legend order
        private final List<YearMonth> months;           // x axis, ascending
        private final Map<String, double[]> values;     // category -> per-month totals
        private final boolean deviation;                 // plot value - mean instead of value
        private double max, min;                         // data range across all series
        private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);
        private final DateTimeFormatter xFmt = DateTimeFormatter.ofPattern("MMM ''yy", Locale.US);
        private final List<Transaction> txns;                    // retained for point drill-down
        private final Set<String> topSet;                // categories drawn as their own line
        private final boolean hasOther;                  // whether an "Other" line exists
        private int[][] ptX, ptY;                        // last-painted point coords [series][month]
        private int selSeries = -1, selMonth = -1;       // selected point, -1 = none
        private final List<Rectangle> legendHit = new ArrayList<>(); // legend row hit boxes
        private boolean pressing;                        // true while a legend label is held
    }

    /** A plain table of each category and its monthly-spending standard deviation. */
    @SuppressWarnings("serial")
    static final class StdDevPanel extends JPanel {
        StdDevPanel(List<CategoryTotal> stdDevs) {
            super(new BorderLayout());
            NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);

            Object[][] rows = new Object[stdDevs.size()][2];
            for (int i = 0; i < stdDevs.size(); i++) {
                rows[i][0] = stdDevs.get(i).category();
                rows[i][1] = money.format(stdDevs.get(i).total());
            }
            DefaultTableModel model =
                    new DefaultTableModel(rows, new Object[] {"Category", "Std Dev"}) {
                        @Override public boolean isCellEditable(int r, int c) { return false; }
                    };
            JTable table = new JTable(model);
            table.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
            table.setRowHeight(28);
            table.getTableHeader().setReorderingAllowed(false);
            table.getTableHeader().setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            table.getColumnModel().getColumn(0).setPreferredWidth(260);
            DefaultTableCellRenderer right = new DefaultTableCellRenderer();
            right.setHorizontalAlignment(SwingConstants.RIGHT);
            table.getColumnModel().getColumn(1).setCellRenderer(right);
            table.getColumnModel().getColumn(1).setPreferredWidth(100);

            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(new Dimension(380, 560));
            JPanel center = new JPanel();       // keep the table narrow, don't stretch it
            center.add(scroll);
            add(center, BorderLayout.CENTER);
        }
    }

    // ---- entry point ------------------------------------------------------

    public static void main(String[] args) {
        Path csv = (args.length > 0) ? Path.of(args[0]) : chooseOrDefault();

        if (csv == null) {
            System.out.println("No file selected.");
            return;
        }

        // Load once, unfiltered; each report below filters it for its own purpose.
        List<Transaction> all = loadAllTransactions(csv);
        if (all.isEmpty()) {
            System.out.println("No rows found in " + csv);
            return;
        }

        List<Transaction> spending = toSpendingTransactions(all);
        List<CategoryTotal> totals = categoryTotals(spending);
        totals.forEach(ct -> System.out.printf(Locale.US, "%-30s %,12.2f%n",
                ct.category(), ct.total()));
        SwingUtilities.invokeLater(
                () -> showCharts(csv.getFileName().toString(), spending, totals));

        List<CheckEntry> checks = findUncategorizedChecks(all);
        printUncategorizedChecks(checks);
        writeUncategorizedChecks(checks, UNCATEGORIZED_CHECKS_OUTPUT);
        System.out.printf(Locale.US, "Wrote %d uncategorized checks to %s%n",
                checks.size(), UNCATEGORIZED_CHECKS_OUTPUT.toAbsolutePath());
        printUncategorized(all);
    }

    // ---- fields -----------------------------------------------------------

    /** Max distinct category lines drawn on the time chart; the rest = "Other". */
    private static final int MAX_SERIES = 100;

    /**
     * Charts/stats include only spending on or after this date; set to null to
     * include everything. Undated transactions are never dropped by this filter.
     */
    private static final LocalDate START_DATE = LocalDate.of(2026, 1, 1); // null = no filter

    /**
     * Deviation panes only: categories whose monthly spending std dev is below
     * this many dollars are dropped as noise. The survivors are then split at
     * their median into the High and Low deviation panes.
     */
    private static final double DEVIATION_MIN_STDDEV = 20;

    /**
     * Deviation pane only: category name prefixes always dropped regardless of
     * std dev (big, lumpy, irregular spikes that swamp the chart). A prefix like
     * "Auto & Transport" drops that category and all its sub-categories.
     */
    private static final Set<String> DEVIATION_EXCLUDED =
            Set.of("Taxes:Federal Tax", "Auto & Transport");

    /**
     * Categories to omit entirely. Account-to-account transfers and card
     * payments are dropped via the CSV's own "Exclusion" flag; this set only
     * removes income and Simplifi's generic transfer categories.
     */
    private static final Set<String> EXCLUDED_CATEGORIES =
            Set.of("Credit Card Payment", "Transfer",
                    "Personal Income", "Personal Income:Paycheck",
                    "Personal Income:Interest Earned");

    /** Where the uncategorized-checks report is written, matching the old standalone tool's default. */
    private static final Path UNCATEGORIZED_CHECKS_OUTPUT =
            Path.of("uncategorized-checks.csv");

    /** Payee pattern for a paper check, e.g. "Check 1234". */
    private static final Pattern CHECK_PAYEE =
            Pattern.compile("(?i)^check\\s+(\\d+)\\s*$");

    private static final String[] DATE_PATTERNS = {
        "MMM d, yyyy", "MMMM d, yyyy",          // Simplifi: "Sep 12, 2026"
        "M/d/yyyy", "MM/dd/yyyy", "M/d/yy", "MM/dd/yy",
        "yyyy/M/d", "M-d-yyyy", "MM-dd-yyyy", "d-MMM-yyyy", "d MMM yyyy"
    };
}