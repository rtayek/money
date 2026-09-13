package money;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
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

    public static void main(String[] args) {
        Path csv = (args.length > 0) ? Path.of(args[0]) : chooseFile();
        if (csv == null) {
            System.out.println("No file selected.");
            return;
        }
        List<CategoryTotal> data = loadSpendingByCategory(csv);
        if (data.isEmpty()) {
            System.out.println("No spending rows found in " + csv);
            return;
        }
        data.forEach(ct -> System.out.printf(Locale.US, "%-30s %,12.2f%n",
                ct.category(), ct.total()));
        SwingUtilities.invokeLater(() -> showChart(csv.getFileName().toString(), data));
    }

    // ---- CSV loading ----------------------------------------------------

    /**
     * Parses the CSV, locating the "Category" and "Amount" columns by header
     * name (order-independent). Sums outflows (negative amounts) per category
     * and returns them as positive totals, sorted largest first.
     */
    static List<CategoryTotal> loadSpendingByCategory(Path csv) {
        List<String> lines;
        try {
            lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + csv, e);
        }
        if (lines.isEmpty()) return List.of();

        List<String> header = parseCsvLine(lines.get(0));
        int catCol = indexOfHeader(header, "Category");
        int amtCol = indexOfHeader(header, "Amount");
        if (catCol < 0 || amtCol < 0) {
            throw new IllegalStateException(
                    "CSV must have 'Category' and 'Amount' columns; found: " + header);
        }

        Map<String, Double> totals = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) continue;
            List<String> fields = parseCsvLine(raw);
            if (amtCol >= fields.size() || catCol >= fields.size()) continue;

            double amount = parseAmount(fields.get(amtCol));
            if (amount >= 0) continue; // only outflows count as spending

            String category = fields.get(catCol).strip();
            if (category.isEmpty()) category = "(Uncategorized)";
            totals.merge(category, -amount, Double::sum);
        }

        List<CategoryTotal> out = new ArrayList<>();
        totals.forEach((k, v) -> out.add(new CategoryTotal(k, v)));
        out.sort(Comparator.comparingDouble(CategoryTotal::total).reversed());
        return out;
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
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open Simplifi CSV export");
        fc.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        int r = fc.showOpenDialog(null);
        return (r == JFileChooser.APPROVE_OPTION) ? fc.getSelectedFile().toPath() : null;
    }

    private static void showChart(String title, List<CategoryTotal> data) {
        JFrame frame = new JFrame("Spending by Category — " + title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(new BarChartPanel(data), BorderLayout.CENTER);
        frame.setSize(900, Math.max(400, 60 + data.size() * 34));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
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
            float hue = (i * 0.61803399f) % 1f; // golden-ratio hue spread
            return Color.getHSBColor(hue, 0.55f, 0.80f);
        }
    }
}
