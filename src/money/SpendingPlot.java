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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;

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
    record Txn(LocalDate date, String category, double amount) {}

    /** Max distinct category lines drawn on the time chart; the rest = "Other". */
    private static final int MAX_SERIES = 20;

    /** Categories to omit entirely (transfers/savings/income, not real spending). */
    private static final Set<String> EXCLUDED_CATEGORIES =
            Set.of("Regular Savings", "Credit Card Payment", "Transfer",
                    "Adv Plus Banking", "Traditional Gold Card",
                    "Delta SkyMiles\u00AE Gold Card",
                    "Personal Income", "Personal Income:Paycheck",
                    "Personal Income:Interest Earned");

    public static void main(String[] args) {
        //Path csv = (args.length > 0) ? Path.of(args[0]) : chooseFile();
        Path csv = Path.of("all.csv");
        
        if (csv == null) {
            System.out.println("No file selected.");
            return;
        }
        List<Txn> txns = loadTransactions(csv);
        if (txns.isEmpty()) {
            System.out.println("No spending rows found in " + csv);
            return;
        }
        List<CategoryTotal> totals = categoryTotals(txns);
        totals.forEach(ct -> System.out.printf(Locale.US, "%-30s %,12.2f%n",
                ct.category(), ct.total()));
        SwingUtilities.invokeLater(
                () -> showCharts(csv.getFileName().toString(), txns, totals));
    }

    // ---- CSV loading ----------------------------------------------------

    /**
     * Parses the CSV into spending transactions (outflows only), locating the
     * "Date", "Category" and "Amount" columns by header name (order-independent).
     */
    static List<Txn> loadTransactions(Path csv) {
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
        if (catCol < 0 || amtCol < 0) {
            throw new IllegalStateException(
                    "CSV must have 'Category' and 'Amount' columns; found: " + header);
        }

        List<Txn> txns = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) continue;
            List<String> fields = parseCsvLine(raw);
            if (amtCol >= fields.size() || catCol >= fields.size()) continue;

            double amount = parseAmount(fields.get(amtCol));
            if (amount >= 0) continue; // only outflows count as spending

            String category = fields.get(catCol).strip();
            if (category.isEmpty()) category = "(Uncategorized)";
            if (EXCLUDED_CATEGORIES.contains(category)) continue;

            LocalDate date = (dateCol >= 0 && dateCol < fields.size())
                    ? parseDate(fields.get(dateCol)) : null;

            txns.add(new Txn(date, category, -amount));
        }
        return txns;
    }

    /** Aggregates transactions into per-category totals, sorted largest first. */
    static List<CategoryTotal> categoryTotals(List<Txn> txns) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (Txn t : txns) totals.merge(t.category(), t.amount(), Double::sum);
        List<CategoryTotal> out = new ArrayList<>();
        totals.forEach((k, v) -> out.add(new CategoryTotal(k, v)));
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

    private static void showCharts(String title, List<Txn> txns,
                                   List<CategoryTotal> totals) {
        JFrame frame = new JFrame("Spending — " + title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("By Category", new BarChartPanel(totals));
        tabs.addTab("Over Time", new TimeSeriesPanel(txns, totals));

        frame.add(tabs, BorderLayout.CENTER);
        frame.setSize(960, Math.max(500, 60 + totals.size() * 34));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
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
        private final double max;
        private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);
        private final DateTimeFormatter xFmt = DateTimeFormatter.ofPattern("MMM ''yy", Locale.US);
        private final List<Rectangle> legendHit = new ArrayList<>(); // legend row hit boxes
        private int selected = -1;                       // highlighted series, -1 = none
        private boolean blinkOn = true;                  // current blink phase
        private Timer blinkTimer;                         // drives the blink burst

        TimeSeriesPanel(List<Txn> txns, List<CategoryTotal> totals) {
            // Which categories get their own line; everything else -> "Other".
            List<String> top = new ArrayList<>();
            for (int i = 0; i < totals.size() && i < MAX_SERIES; i++) {
                top.add(totals.get(i).category());
            }
            boolean hasOther = totals.size() > MAX_SERIES;

            TreeSet<YearMonth> monthSet = new TreeSet<>();
            for (Txn t : txns) {
                if (t.date() != null) monthSet.add(YearMonth.from(t.date()));
            }
            this.months = new ArrayList<>(monthSet);

            Map<String, Integer> idx = new LinkedHashMap<>();
            for (int i = 0; i < months.size(); i++) idx.put(months.get(i).toString(), i);

            this.series = new ArrayList<>(top);
            if (hasOther) series.add("Other");

            this.values = new LinkedHashMap<>();
            for (String s : series) values.put(s, new double[months.size()]);

            for (Txn t : txns) {
                if (t.date() == null) continue;
                String key = top.contains(t.category()) ? t.category()
                        : (hasOther ? "Other" : t.category());
                double[] arr = values.get(key);
                if (arr == null) continue;
                arr[idx.get(YearMonth.from(t.date()).toString())] += t.amount();
            }

            double m = 0;
            for (double[] arr : values.values())
                for (double v : arr) m = Math.max(m, v);
            this.max = (m == 0) ? 1 : m;

            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(960, 520));

            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    for (int i = 0; i < legendHit.size(); i++) {
                        if (legendHit.get(i).contains(e.getPoint())) { select(i); return; }
                    }
                }
            });
        }

        /** Toggles the highlighted series and runs a short blink burst on it. */
        private void select(int i) {
            selected = (selected == i) ? -1 : i;
            if (blinkTimer != null) blinkTimer.stop();
            blinkOn = true;
            if (selected >= 0) {
                int[] toggles = {0};
                blinkTimer = new Timer(300, ev -> {
                    blinkOn = !blinkOn;
                    if (++toggles[0] >= 6) { ((Timer) ev.getSource()).stop(); blinkOn = true; }
                    repaint();
                });
                blinkTimer.start();
            }
            repaint();
        }

        /** A washed-out version of a series color, for the non-selected lines. */
        private static Color fade(Color c) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 45);
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

            // y grid + currency labels
            int ticks = 5;
            g.setColor(new Color(0xEE, 0xEE, 0xEE));
            for (int t = 0; t <= ticks; t++) {
                int y = top + plotH - (int) Math.round(plotH * (t / (double) ticks));
                g.setColor(new Color(0xEE, 0xEE, 0xEE));
                g.drawLine(left, y, left + plotW, y);
                g.setColor(Color.GRAY);
                String lab = money.format(max * t / ticks);
                drawRightAligned(g, lab, left - 6, y + 4);
            }

            // axes
            g.setColor(new Color(0xAA, 0xAA, 0xAA));
            g.drawLine(left, top, left, top + plotH);
            g.drawLine(left, top + plotH, left + plotW, top + plotH);

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

            // one line per series (selected one is bold; others faded)
            for (int s = 0; s < series.size(); s++) {
                boolean sel = (s == selected);
                if (sel && !blinkOn) continue;      // blink: skip on the off phase
                double[] arr = values.get(series.get(s));
                g.setColor(selected >= 0 && !sel ? fade(seriesColor(s)) : seriesColor(s));
                g.setStroke(new BasicStroke(sel ? 4f : 2f));
                int dot = sel ? 3 : 2;
                int prevX = 0, prevY = 0;
                for (int i = 0; i < n; i++) {
                    int x = xAt.applyAsInt(i);
                    int y = top + plotH - (int) Math.round(plotH * (arr[i] / max));
                    if (i > 0) g.drawLine(prevX, prevY, x, y);
                    g.fillOval(x - dot, y - dot, 2 * dot, 2 * dot);
                    prevX = x; prevY = y;
                }
            }

            // legend (click a row to highlight/blink its line)
            legendHit.clear();
            int lx = left + plotW + 14;
            int ly = top + 4;
            for (int s = 0; s < series.size(); s++) {
                g.setColor(seriesColor(s));
                g.fillRect(lx, ly, 12, 12);
                g.setColor(s == selected ? Color.BLACK : Color.DARK_GRAY);
                g.setFont(getFont().deriveFont(s == selected ? Font.BOLD : Font.PLAIN, 11f));
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
}
