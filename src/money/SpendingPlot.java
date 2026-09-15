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
import javax.swing.Timer;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
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

    /** Max distinct category lines drawn on the time chart; the rest = "Other". */
    private static final int MAX_SERIES = 100;

    /** Close the chart window after this many minutes. */
    private static final int AUTO_CLOSE_MINUTES = 10;

    /**
     * Deviation pane only: categories whose monthly spending std dev is below
     * this many dollars are dropped as noise.
     */
    private static final double DEVIATION_MIN_STDDEV = 100;

    /**
     * Deviation pane only: category name prefixes always dropped regardless of
     * std dev (big, lumpy, irregular spikes that swamp the chart). A prefix like
     * "Auto & Transport" drops that category and all its sub-categories.
     */
    private static final Set<String> DEVIATION_EXCLUDED =
            Set.of("Taxes:Federal Tax", "Auto & Transport");

    /** True if a category should be excluded from the deviation pane. */
    private static boolean isDeviationExcluded(String category) {
        for (String prefix : DEVIATION_EXCLUDED)
            if (category.equals(prefix) || category.startsWith(prefix + ":")) return true;
        return false;
    }

    /**
     * Categories to omit entirely. Account-to-account transfers and card
     * payments are dropped via the CSV's own "Exclusion" flag; this set only
     * removes income and Simplifi's generic transfer categories.
     */
    private static final Set<String> EXCLUDED_CATEGORIES =
            Set.of("Credit Card Payment", "Transfer",
                    "Personal Income", "Personal Income:Paycheck",
                    "Personal Income:Interest Earned");

    public static void main(String[] args) {
        //Path csv = (args.length > 0) ? Path.of(args[0]) : chooseFile();
        Path csv = Path.of("all.csv");
        
        if (csv == null) {
            System.out.println("No file selected.");
            return;
        }
        List<Transaction> transactions = loadTransactions(csv);
        if (transactions.isEmpty()) {
            System.out.println("No spending rows found in " + csv);
            return;
        }
        List<CategoryTotal> totals = categoryTotals(transactions);
        totals.forEach(ct -> System.out.printf(Locale.US, "%-30s %,12.2f%n",
                ct.category(), ct.total()));
        SwingUtilities.invokeLater(
                () -> showCharts(csv.getFileName().toString(), transactions, totals));
        printUncategorizedChecks(transactions);
        printUncategorized(transactions);
    }

    // ---- CSV loading ----------------------------------------------------

    /**
     * Parses the CSV into spending transactions (outflows only), locating the
     * "Date", "Category" and "Amount" columns by header name (order-independent).
     */
    static List<Transaction> loadTransactions(Path csv) {
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
            if (amount >= 0) continue; // only outflows count as spending

            String category = fields.get(catCol).strip();
            if (category.isEmpty()) category = "(Uncategorized)";
            if (EXCLUDED_CATEGORIES.contains(category)) continue;

            LocalDate date = (dateCol >= 0 && dateCol < fields.size())
                    ? parseDate(fields.get(dateCol)) : null;
            // Drop the current (still-accumulating) month; it is only partial.
            if (date != null && YearMonth.from(date).equals(YearMonth.now())) continue;
            String payee = (payeeCol >= 0 && payeeCol < fields.size())
                    ? fields.get(payeeCol).strip() : "";

            txns.add(new Transaction(date, category, payee, -amount));
        }
        return txns;
    }

    /** Payee pattern for a paper check, e.g. "Check 1234". */
    private static final Pattern CHECK_PAYEE =
            Pattern.compile("(?i)^check\\s+(\\d+)\\s*$");

    /** Prints every uncategorized paper check in {@code transactions} to stdout. */
    static void printUncategorizedChecks(List<Transaction> transactions) {
        List<Transaction> checks = new ArrayList<>();
        for (Transaction t : transactions) {
            String category = t.category() == null ? "" : t.category().strip();
            boolean uncategorized = category.isEmpty()
                    || category.equalsIgnoreCase("Uncategorized")
                    || category.equalsIgnoreCase("(Uncategorized)");
            if (!uncategorized) continue;
            if (t.payee() == null || !CHECK_PAYEE.matcher(t.payee().strip()).matches()) continue;
            checks.add(t);
        }
        checks.sort(Comparator.comparing(Transaction::date,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        if (checks.isEmpty()) {
            System.out.println("No uncategorized checks.");
            return;
        }
        System.out.printf(Locale.US, "%-12s %-8s %12s%n", "Date", "Check", "Amount");
        for (Transaction t : checks) {
            Matcher m = CHECK_PAYEE.matcher(t.payee().strip());
            m.matches();
            System.out.printf(Locale.US, "%-12s %-8s %,12.2f%n",
                    t.date(), m.group(1), t.amount());
        }
    }

    /** True if a transaction has no meaningful category assigned. */
    private static boolean isUncategorized(Transaction t) {
        String category = t.category() == null ? "" : t.category().strip();
        return category.isEmpty()
                || category.equalsIgnoreCase("Uncategorized")
                || category.equalsIgnoreCase("(Uncategorized)");
    }

    /**
     * Prints every uncategorized transaction that is not a paper check to stdout
     * (paper checks are covered by {@link #printUncategorizedChecks}).
     */
    static void printUncategorized(List<Transaction> transactions) {
        List<Transaction> uncategorized = new ArrayList<>();
        for (Transaction t : transactions) {
            if (!isUncategorized(t)) continue;
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
            total += t.amount();
            System.out.printf(Locale.US, "%-12s %,12.2f  %s%n",
                    t.date(), t.amount(), t.payee());
        }
        System.out.printf(Locale.US, "%-12s %,12.2f%n", "Total", total);
    }

    /** Aggregates transactions into per-category totals, sorted largest first. */
    static List<CategoryTotal> categoryTotals(List<Transaction> transactionss) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (Transaction t : transactionss) totals.merge(t.category(), t.amount(), Double::sum);
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
            byCat.computeIfAbsent(t.category(), k -> new double[n])
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

    private static final String[] DATE_PATTERNS = {
        "MMM d, yyyy", "MMMM d, yyyy",          // Simplifi: "Sep 12, 2026"
        "M/d/yyyy", "MM/dd/yyyy", "M/d/yy", "MM/dd/yy",
        "yyyy/M/d", "M-d-yyyy", "MM-dd-yyyy", "d-MMM-yyyy", "d MMM yyyy"
    };

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
        // Deviation pane: drop categories whose monthly std dev is below the
        // threshold (the flattest lines) to reduce clutter.
        List<CategoryTotal> sds = categoryMonthlyStdDevs(transactions);
        Set<String> lowestDev = new HashSet<>();
        for (CategoryTotal sd : sds)
            if (sd.total() < DEVIATION_MIN_STDDEV) lowestDev.add(sd.category());
        List<Transaction> devTxns = new ArrayList<>();
        for (Transaction t : transactions)
            if (!lowestDev.contains(t.category())
                    && !isDeviationExcluded(t.category())) devTxns.add(t);
        tabs.addTab("Deviation",
                new TimeSeriesPanel(devTxns, categoryTotals(devTxns), true));

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
        closeAfter(frame, AUTO_CLOSE_MINUTES);
    }

    /** Starts a one-shot Swing timer that closes the supplied window. */
    static Timer closeAfter(Window window, int minutes) {
        if (minutes <= 0) throw new IllegalArgumentException("minutes must be positive");
        int delayMillis = Math.multiplyExact(minutes, 60_000);
        Timer timer = new Timer(delayMillis, event -> window.dispose());
        timer.setRepeats(false);
        timer.start();
        return timer;
    }

    private static Color seriesColor(int i) {
        float hue = (i * 0.61803399f) % 1f; // golden-ratio hue spread
        return Color.getHSBColor(hue, 0.60f, 0.80f);
    }

    /** Horizontal bar chart drawn with Java2D. */
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

        private void drawRightAligned(Graphics2D g, String s, int rightEdge, int y) {
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
    static final class TimeSeriesPanel extends JPanel {
        private final List<String> series;              // category names, in legend order
        private final List<YearMonth> months;           // x axis, ascending
        private final Map<String, double[]> values;     // category -> per-month totals
        private final boolean deviation;                 // plot value - mean instead of value
        private final double max, min;                   // data range across all series
        private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);
        private final DateTimeFormatter xFmt = DateTimeFormatter.ofPattern("MMM ''yy", Locale.US);
        private final List<Transaction> txns;                    // retained for point drill-down
        private final Set<String> topSet;                // categories drawn as their own line
        private final boolean hasOther;                  // whether an "Other" line exists
        private int[][] ptX, ptY;                        // last-painted point coords [series][month]
        private int selSeries = -1, selMonth = -1;       // selected point, -1 = none
        private final List<Rectangle> legendHit = new ArrayList<>(); // legend row hit boxes
        private boolean pressing;                        // true while a legend label is held

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

            double hi = 0, lo = 0;
            for (double[] arr : values.values())
                for (double v : arr) { hi = Math.max(hi, v); lo = Math.min(lo, v); }
            this.max = (hi == 0) ? 1 : hi;
            this.min = lo;

            this.txns = txns;
            this.hasOther = series.contains("Other");
            this.topSet = new HashSet<>(series);
            this.topSet.remove("Other");

            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(960, 520));

            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    for (int i = 0; i < legendHit.size(); i++) {
                        if (legendHit.get(i).contains(e.getPoint())) {
                            selSeries = (selSeries == i) ? -1 : i;
                            selMonth = -1;              // clear any point drill-down
                            pressing = (selSeries >= 0);
                            repaint();
                            return;
                        }
                    }
                    pickPoint(e.getX(), e.getY());      // otherwise treat as a point click
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (pressing) { pressing = false; repaint(); }
                }
            });
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

            // y grid at even $1,000 multiples; deviation mode spans +/- around zero.
            double step, axisMin, axisMax;
            int loTick, hiTick;
            if (deviation) {
                double mag = Math.max(Math.abs(max), Math.abs(min));
                if (mag == 0) mag = 1;
                step = thousandStep(mag);
                hiTick = (int) Math.ceil(mag / step);
                loTick = -hiTick;
                axisMax = hiTick * step;
                axisMin = -axisMax;
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

        private void drawRightAligned(Graphics2D g, String s, int rightEdge, int y) {
            Rectangle2D b = g.getFontMetrics().getStringBounds(s, g);
            g.drawString(s, Math.max(2, (int) (rightEdge - b.getWidth())), y);
        }
    }

    /** A plain table of each category and its monthly-spending standard deviation. */
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
}
