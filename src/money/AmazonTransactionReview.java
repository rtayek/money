package money;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Matches Simplifi Amazon charges to Amazon's order-history export. */
public final class AmazonTransactionReview {

    public static void main(String[] args) {
        if (args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: AmazonTransactionReview [simplifi.csv] [amazon.zip] [output.csv]");
        }
        Path simplifi = args.length > 0 ? Path.of(args[0]) : Path.of("all.csv");
        Path amazon = args.length > 1 ? Path.of(args[1])
                : Path.of("private", "amazon", "Your Orders.zip");
        Path output = args.length > 2 ? Path.of(args[2])
                : Path.of("reports", "amazon-transaction-review.csv");

        Result result = create(simplifi, amazon, output);
        System.out.printf(Locale.US, "Wrote %d Amazon charges to %s%n",
                result.rows(), output.toAbsolutePath());
        System.out.printf(Locale.US, "Wrote %d action items to %s%n",
                result.actionItems(), actionOutput(output).toAbsolutePath());
        result.matchCounts().forEach((status, count) ->
                System.out.printf(Locale.US, "%-18s %4d%n", status + ":", count));
        System.out.printf(Locale.US, "Recommendations:    %4d%n", result.recommendations());
        System.out.printf(Locale.US, "Mirror rows dropped:%4d%n", result.mirrorDuplicatesDropped());
    }

    static Result create(Path simplifi, Path amazon, Path output) {
        Table simplifiTable = readCsv(simplifi);
        List<Candidate> candidates = readCandidates(amazon);
        Map<Long, List<Candidate>> candidatesByAmount = new HashMap<>();
        for (Candidate candidate : candidates) {
            candidatesByAmount.computeIfAbsent(candidate.cents(), ignored -> new ArrayList<>())
                    .add(candidate);
        }

        MirrorResult mirrorResult = withoutMirroredDuplicates(readCharges(simplifiTable));
        List<Charge> charges = mirrorResult.charges();
        List<ReviewRow> review = review(charges, candidatesByAmount);
        review.sort(Comparator.comparing(ReviewRow::date).thenComparingInt(ReviewRow::sourceRow));
        validate(charges, review);
        write(output, review);
        List<ReviewRow> actionItems = review.stream().filter(ReviewRow::actionable).toList();
        write(actionOutput(output), actionItems);

        Map<String, Integer> matchCounts = new TreeMap<>();
        int recommendations = 0;
        for (ReviewRow row : review) {
            matchCounts.merge(row.matchStatus(), 1, Integer::sum);
            if (!row.recommendedCategory().isBlank()) recommendations++;
        }
        return new Result(review.size(), actionItems.size(), matchCounts, recommendations,
                mirrorResult.dropped());
    }

    private static List<Charge> readCharges(Table table) {
        List<Charge> charges = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            List<String> row = table.rows().get(i);
            String payee = table.get(row, "Payee").strip();
            long cents = cents(table.get(row, "Amount"));
            if (!amazonPayee.matcher(payee).find() || cents >= 0) continue;
            charges.add(new Charge(i + 2,
                    parseDate(table.get(row, "Date")),
                    table.get(row, "Account").strip(),
                    payee,
                    -cents,
                    table.get(row, "Category").strip(),
                    singleLine(table.get(row, "Notes")),
                    table.get(row, "Exclusion").strip()));
        }
        return charges;
    }

    private static MirrorResult withoutMirroredDuplicates(List<Charge> charges) {
        Map<String, List<Charge>> primaryByKey = new HashMap<>();
        for (Charge charge : charges) {
            if (isPrimaryMirrorAccount(charge.account())) {
                primaryByKey.computeIfAbsent(charge.mirrorKey(), ignored -> new ArrayList<>())
                        .add(charge);
            }
        }
        List<Charge> kept = new ArrayList<>();
        int dropped = 0;
        for (Charge charge : charges) {
            List<Charge> twins = primaryByKey.get(charge.mirrorKey());
            if (isMirrorAliasAccount(charge.account()) && twins != null && !twins.isEmpty()) {
                Charge primary = twins.remove(twins.size() - 1);
                if (!charge.category().equalsIgnoreCase(primary.category())) {
                    System.out.printf(Locale.US,
                            "Warning: mirrored Amazon copies have different categories: "
                                    + "%s | %s | %s | dropping %s [%s], keeping %s [%s]%n",
                            charge.date(), charge.payee(), money(charge.cents()),
                            charge.account(), charge.category(), primary.account(),
                            primary.category());
                }
                dropped++;
            } else {
                kept.add(charge);
            }
        }
        return new MirrorResult(List.copyOf(kept), dropped);
    }

    private static boolean isMirrorAliasAccount(String account) {
        return normalizedAccount(account).equals("americanexpresstraditionalgold");
    }

    private static boolean isPrimaryMirrorAccount(String account) {
        return normalizedAccount(account).equals("traditionalgoldcard");
    }

    private static String normalizedAccount(String account) {
        return account.toLowerCase(Locale.ROOT).replace("(r)", "")
                .replaceAll("[^a-z0-9]", "");
    }

    private static List<Candidate> readCandidates(Path amazon) {
        try (ZipFile zip = new ZipFile(amazon.toFile(), StandardCharsets.UTF_8)) {
            Table retail = readCsv(zip, retailEntry);
            Table digital = readCsv(zip, digitalEntry);
            List<Candidate> candidates = new ArrayList<>();
            candidates.addAll(retailCandidates(retail));
            candidates.addAll(digitalCandidates(digital));
            return candidates;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + amazon, e);
        }
    }

    private static List<Candidate> retailCandidates(Table table) {
        Map<String, List<List<String>>> groups = new LinkedHashMap<>();
        for (List<String> row : table.rows()) {
            if (!table.get(row, "Order Status").equalsIgnoreCase("Closed")) continue;
            long amount = cents(table.get(row, "Total Amount"));
            if (amount <= 0) continue;
            String orderId = table.get(row, "Order ID").strip();
            groups.computeIfAbsent(orderId + "\u0000" + amount,
                    ignored -> new ArrayList<>()).add(row);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (List<List<String>> rows : groups.values()) {
            List<String> first = rows.get(0);
            LinkedHashSet<LocalDate> orderDates = new LinkedHashSet<>();
            LinkedHashSet<LocalDate> shipDates = new LinkedHashSet<>();
            TreeSet<String> products = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            TreeSet<String> departments = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (List<String> row : rows) {
                addDate(orderDates, table.get(row, "Order Date"));
                addDate(shipDates, table.get(row, "Ship Date"));
                addText(products, table.get(row, "Product Name"));
                addText(departments, table.get(row, "Department"));
            }
            LinkedHashSet<LocalDate> matchDates = new LinkedHashSet<>(orderDates);
            matchDates.addAll(shipDates);
            candidates.add(new Candidate("Retail",
                    cents(table.get(first, "Total Amount")),
                    table.get(first, "Order ID").strip(),
                    List.copyOf(orderDates), List.copyOf(shipDates), List.copyOf(matchDates),
                    List.copyOf(products), List.copyOf(departments)));
        }
        return candidates;
    }

    private static List<Candidate> digitalCandidates(Table table) {
        Map<String, List<List<String>>> groups = new LinkedHashMap<>();
        for (List<String> row : table.rows()) {
            if (!table.get(row, "Order Status").equalsIgnoreCase("SUCCESS")) continue;
            groups.computeIfAbsent(table.get(row, "Order ID").strip(),
                    ignored -> new ArrayList<>()).add(row);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<List<String>>> entry : groups.entrySet()) {
            long amount = 0;
            LinkedHashSet<LocalDate> orderDates = new LinkedHashSet<>();
            TreeSet<String> products = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (List<String> row : entry.getValue()) {
                amount += cents(table.get(row, "Transaction Amount"));
                addDate(orderDates, table.get(row, "Order Date"));
                addText(products, table.get(row, "Product Name"));
            }
            if (amount <= 0) continue;
            candidates.add(new Candidate("Digital", amount, entry.getKey(),
                    List.copyOf(orderDates), List.of(), List.copyOf(orderDates),
                    List.copyOf(products), List.of("Digital Content")));
        }
        return candidates;
    }

    private static List<ReviewRow> review(List<Charge> charges,
                                           Map<Long, List<Candidate>> candidatesByAmount) {
        List<ChargeOptions> options = new ArrayList<>();
        for (Charge charge : charges) {
            List<NearCandidate> withinSeven = new ArrayList<>();
            for (Candidate candidate : candidatesByAmount.getOrDefault(charge.cents(), List.of())) {
                int difference = candidate.daysFrom(charge.date());
                if (difference <= 7) withinSeven.add(new NearCandidate(candidate, difference));
            }
            int minimum = withinSeven.stream().mapToInt(NearCandidate::days).min().orElse(-1);
            List<Candidate> nearest = withinSeven.stream()
                    .filter(candidate -> candidate.days() == minimum)
                    .map(NearCandidate::candidate)
                    .toList();
            options.add(new ChargeOptions(charge, List.copyOf(withinSeven), minimum, nearest));
        }

        List<MatchEdge> edges = new ArrayList<>();
        for (ChargeOptions option : options) {
            if (option.nearest().size() != 1) continue;
            for (NearCandidate near : option.withinSeven()) {
                edges.add(new MatchEdge(option.charge(), near.candidate(), near.days()));
            }
        }
        edges.sort(Comparator.comparingInt(MatchEdge::days)
                .thenComparingInt(edge -> edge.charge().sourceRow())
                .thenComparing(edge -> edge.candidate().kind())
                .thenComparing(edge -> edge.candidate().orderId()));

        Map<Integer, MatchEdge> assigned = new HashMap<>();
        Set<Candidate> used = new HashSet<>();
        for (MatchEdge edge : edges) {
            if (assigned.containsKey(edge.charge().sourceRow()) || used.contains(edge.candidate())) {
                continue;
            }
            assigned.put(edge.charge().sourceRow(), edge);
            used.add(edge.candidate());
        }

        List<ReviewRow> rows = new ArrayList<>();
        for (ChargeOptions option : options) {
            Charge charge = option.charge();
            if (option.nearest().isEmpty()) {
                rows.add(ReviewRow.unmatched(charge, option.withinSeven().size(),
                        "No retail or digital order with the same amount within 7 days."));
            } else if (option.nearest().size() > 1) {
                Recommendation recommendation = recommend(option.nearest());
                rows.add(ReviewRow.ambiguous(charge, option.withinSeven().size(),
                        option.minimum(), option.nearest(), recommendation,
                        assessment(charge, recommendation)));
            } else {
                MatchEdge edge = assigned.get(charge.sourceRow());
                if (edge == null) {
                    rows.add(ReviewRow.unmatched(charge, option.withinSeven().size(),
                            "Matching Amazon order was already assigned to another Simplifi charge."));
                    continue;
                }
                Recommendation recommendation = recommend(edge.candidate());
                String confidence = edge.days() <= 1 ? "High"
                        : edge.days() <= 3 ? "Medium" : "Low";
                rows.add(ReviewRow.matched(charge, option.withinSeven().size(), edge.days(),
                        edge.candidate(), confidence, recommendation,
                        assessment(charge, recommendation)));
            }
        }
        return rows;
    }

    private static String assessment(Charge charge, Recommendation recommendation) {
        return recommendation.category().isBlank() ? "Needs review"
                : recommendation.category().equals(charge.category())
                        ? "Correct" : "Review category";
    }

    private static Recommendation recommend(Candidate candidate) {
        String productText = String.join(" ", candidate.products()).toLowerCase(Locale.ROOT);
        if (productText.contains("kindle unlimited")
                || productText.contains("audible premium plus")) {
            return new Recommendation("Shopping:Books", "High", "Known Amazon subscription");
        }
        if (productText.contains("britbox") || productText.contains("bbc select")
                || productText.contains("acorn tv")
                || productText.contains("prime video ultra")
                || productText.contains("discovery+")) {
            return new Recommendation("Entertainment", "High", "Known Amazon subscription");
        }
        if (productText.contains("prime membership fee")) {
            return new Recommendation("Prime", "High", "Known Amazon subscription");
        }
        Set<String> categories = new LinkedHashSet<>();
        boolean medium = false;
        for (String department : candidate.departments()) {
            String normalized = department.toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "paperback", "hardcover", "mass market paperback", "spiral-bound", "book", "books" ->
                    categories.add("Shopping:Books");
                case "electronics", "personal computers", "video game" ->
                    categories.add("Shopping:Electronics");
                case "grocery" -> categories.add("Groceries");
                case "lawn & patio" -> categories.add("Home:Garden");
                case "tools & home improvement" -> categories.add("Home:Home Improvement");
                case "automotive" -> categories.add("Auto & Transport:Service & Parts");
                case "apparel", "shoes" -> categories.add("Shopping:Clothing");
                case "ecard gift certificate", "plastic gift certificate" -> categories.add("Gifts");
                case "kitchen" -> {
                    categories.add("Home:Furnishings");
                    medium = true;
                }
                case "office product", "toy" -> {
                    categories.add("Shopping");
                    medium = true;
                }
                case "pet supplies" -> categories.add("Pets");
                default -> { }
            }
        }
        if (categories.size() == 1) {
            return new Recommendation(categories.iterator().next(), medium ? "Medium" : "High",
                    "Amazon department");
        }
        if (categories.size() > 1) {
            return new Recommendation("", "Low", "Items map to multiple categories");
        }
        if (candidate.kind().equals("Digital")
                && productText.matches("(?is).*\\b(kindle|e-?book)\\b.*")) {
            return new Recommendation("Shopping:Books", "Medium", "Digital product name");
        }
        return new Recommendation("", "Low", "Department needs review");
    }

    private static Recommendation recommend(List<Candidate> candidates) {
        List<Recommendation> recommendations = candidates.stream()
                .map(AmazonTransactionReview::recommend).toList();
        Set<String> categories = new LinkedHashSet<>();
        for (Recommendation recommendation : recommendations) {
            if (recommendation.category().isBlank()) return noRecommendation;
            categories.add(recommendation.category());
        }
        if (categories.size() == 1) {
            return new Recommendation(categories.iterator().next(), "Medium",
                    "All equally near orders map to the same category");
        }
        return noRecommendation;
    }

    private static void validate(List<Charge> charges, List<ReviewRow> review) {
        if (review.size() != charges.size()) {
            throw new IllegalStateException("Expected " + charges.size()
                    + " review rows but produced " + review.size());
        }
        Set<Integer> sourceRows = new HashSet<>();
        for (ReviewRow row : review) {
            if (!sourceRows.add(row.sourceRow())) {
                throw new IllegalStateException("Duplicate Simplifi row " + row.sourceRow());
            }
        }
    }

    private static void write(Path output, List<ReviewRow> rows) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            StringBuilder csv = new StringBuilder();
            appendCsvRow(csv, headers);
            for (ReviewRow row : rows) appendCsvRow(csv, row.cells());
            Files.writeString(output, csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + output, e);
        }
    }

    private static Path actionOutput(Path output) {
        Path parent = output.getParent();
        return parent == null ? Path.of("amazon-action-items.csv")
                : parent.resolve("amazon-action-items.csv");
    }

    private static void appendCsvRow(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) csv.append(',');
            String value = cells.get(i) == null ? "" : cells.get(i);
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append('\n');
    }

    private static Table readCsv(Path path) {
        try {
            return Table.from(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    private static Table readCsv(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("ZIP entry not found: " + name);
        try (var input = zip.getInputStream(entry)) {
            return Table.from(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (!row.isEmpty() || field.length() > 0) {
            row.add(field.toString());
            rows.add(row);
        }
        if (quoted) throw new IllegalArgumentException("Unterminated quoted CSV field");
        return rows;
    }

    private static long cents(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || cleaned.equals("-") || cleaned.equals(".")) return 0;
        return new BigDecimal(cleaned).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static LocalDate parseDate(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) return null;
        if (text.indexOf('T') >= 0) {
            try {
                return java.time.Instant.parse(text)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            } catch (RuntimeException ignored) { }
            try {
                return java.time.OffsetDateTime.parse(text)
                        .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
            } catch (RuntimeException ignored) { }
        }
        if (text.length() >= 10 && text.charAt(4) == '-' && text.charAt(7) == '-') {
            try {
                return LocalDate.parse(text.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (RuntimeException ignored) { }
        }
        for (DateTimeFormatter formatter : dateFormats) {
            try {
                return LocalDate.parse(text, formatter);
            } catch (RuntimeException ignored) { }
        }
        throw new IllegalArgumentException("Unrecognized date: " + value);
    }

    private static void addDate(Set<LocalDate> dates, String value) {
        if (value == null || value.isBlank()) return;
        try {
            dates.add(parseDate(value));
        } catch (IllegalArgumentException ignored) { }
    }

    private static void addText(Set<String> values, String value) {
        String text = singleLine(value);
        if (!text.isBlank()) values.add(text);
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\R+", " ")
                .replaceAll("\\s+", " ").strip();
    }

    private static String dates(List<LocalDate> dates) {
        return dates.stream().sorted().map(LocalDate::toString)
                .reduce((left, right) -> left + " | " + right).orElse("");
    }

    private static String join(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER)
                .reduce((left, right) -> left + " | " + right).orElse("");
    }

    private static String money(long cents) {
        return String.format(Locale.US, "%.2f", cents / 100.0);
    }

    record Table(List<String> header, List<List<String>> rows, Map<String, Integer> columns) {
        static Table from(String text) {
            List<List<String>> parsed = parseCsv(text);
            if (parsed.isEmpty()) throw new IllegalArgumentException("CSV is empty");
            List<String> header = parsed.get(0);
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                columns.put(header.get(i).strip().toLowerCase(Locale.ROOT), i);
            }
            return new Table(List.copyOf(header),
                    List.copyOf(parsed.subList(1, parsed.size())), Map.copyOf(columns));
        }

        String get(List<String> row, String column) {
            Integer index = columns.get(column.toLowerCase(Locale.ROOT));
            if (index == null) throw new IllegalArgumentException("Missing CSV column: " + column);
            return index < row.size() ? row.get(index) : "";
        }
    }

    record Charge(int sourceRow, LocalDate date, String account, String payee,
                  long cents, String category, String notes, String exclusion) {
        String mirrorKey() {
            return date + "|" + payee.toUpperCase(Locale.ROOT) + "|" + cents;
        }
    }

    record Candidate(String kind, long cents, String orderId,
                     List<LocalDate> orderDates, List<LocalDate> shipDates,
                     List<LocalDate> matchDates, List<String> products,
                     List<String> departments) {
        int daysFrom(LocalDate date) {
            return matchDates.stream()
                    .mapToInt(candidateDate -> (int) Math.abs(
                            ChronoUnit.DAYS.between(date, candidateDate)))
                    .min().orElse(Integer.MAX_VALUE);
        }
    }

    record NearCandidate(Candidate candidate, int days) { }

    record ChargeOptions(Charge charge, List<NearCandidate> withinSeven,
                         int minimum, List<Candidate> nearest) { }

    record MatchEdge(Charge charge, Candidate candidate, int days) { }

    record MirrorResult(List<Charge> charges, int dropped) { }

    record Recommendation(String category, String confidence, String basis) { }

    record ReviewRow(int sourceRow, LocalDate date, String account, String payee,
                     long cents, String currentCategory, String notes, String exclusion,
                     boolean possibleMirrorDuplicate, String matchStatus,
                     String matchConfidence, String dateDifference, int candidateCount,
                     String orderType, String orderId, String orderDates, String shipDates,
                     String amazonAmount, String itemCount, String departments,
                     String products, String recommendedCategory,
                     String recommendationConfidence, String assessment, String reason) {

        static ReviewRow unmatched(Charge charge, int candidateCount, String reason) {
            return base(charge, false, "Unmatched", "None", "", candidateCount,
                    List.of(), "", "", "Low", "Needs review", reason);
        }

        static ReviewRow ambiguous(Charge charge, int candidateCount,
                                   int days, List<Candidate> candidates,
                                   Recommendation recommendation, String assessment) {
            return base(charge, false, "Ambiguous", "Low", Integer.toString(days),
                    candidateCount, candidates, money(charge.cents()),
                    recommendation.category(), recommendation.confidence(), assessment,
                    candidates.size() + " equally near orders share this amount.");
        }

        static ReviewRow matched(Charge charge, int candidateCount,
                                 int days, Candidate candidate, String matchConfidence,
                                 Recommendation recommendation, String assessment) {
            return base(charge, false, "Matched " + candidate.kind().toLowerCase(Locale.ROOT),
                    matchConfidence, Integer.toString(days), candidateCount,
                    List.of(candidate), money(candidate.cents()), recommendation.category(),
                    recommendation.confidence(), assessment,
                    "Unique nearest amount match; " + recommendation.basis() + ".");
        }

        private static ReviewRow base(Charge charge, boolean mirror, String status,
                                      String matchConfidence, String difference,
                                      int candidateCount, List<Candidate> candidates,
                                      String amazonAmount, String recommended,
                                      String recommendationConfidence, String assessment,
                                      String reason) {
            String orderType = join(candidates.stream().map(Candidate::kind).toList());
            String orderId = join(candidates.stream().map(Candidate::orderId).toList());
            String orderDates = join(candidates.stream().map(candidate ->
                    dates(candidate.orderDates())).toList());
            String shipDates = join(candidates.stream().map(candidate ->
                    dates(candidate.shipDates())).toList());
            String departments = join(candidates.stream()
                    .flatMap(candidate -> candidate.departments().stream()).toList());
            String products = join(candidates.stream()
                    .flatMap(candidate -> candidate.products().stream()).toList());
            int itemCount = candidates.stream().mapToInt(candidate -> candidate.products().size()).sum();
            return new ReviewRow(charge.sourceRow(), charge.date(), charge.account(),
                    charge.payee(), charge.cents(), charge.category(), charge.notes(),
                    charge.exclusion(), mirror, status, matchConfidence, difference,
                    candidateCount, orderType, orderId, orderDates, shipDates,
                    amazonAmount, candidates.isEmpty() ? "" : Integer.toString(itemCount),
                    departments, products, recommended, recommendationConfidence,
                    assessment, reason);
        }

        List<String> cells() {
            return List.of(Integer.toString(sourceRow), date.toString(), account, payee,
                    money(cents), currentCategory, notes, exclusion,
                    possibleMirrorDuplicate ? "Yes" : "No", matchStatus, matchConfidence,
                    dateDifference, Integer.toString(candidateCount), orderType, orderId,
                    orderDates, shipDates, amazonAmount, itemCount, departments, products,
                    recommendedCategory, recommendationConfidence, assessment, reason);
        }

        boolean actionable() {
            return matchStatus.equals("Unmatched") || matchStatus.equals("Ambiguous")
                    || assessment.equals("Review category");
        }
    }

    record Result(int rows, int actionItems, Map<String, Integer> matchCounts,
                  int recommendations, int mirrorDuplicatesDropped) { }

    private static final java.util.regex.Pattern amazonPayee =
            java.util.regex.Pattern.compile("amazon|amzn", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String retailEntry = "Your Amazon Orders/Order History.csv";
    private static final String digitalEntry = "Your Amazon Orders/Digital Content Orders.csv";
    private static final List<DateTimeFormatter> dateFormats = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US),
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US),
            DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US),
            DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.US));
    private static final List<String> headers = List.of(
            "Simplifi Row", "Simplifi Date", "Account", "Payee", "Charge Amount",
            "Current Category", "Notes", "Exclusion", "Possible Mirror Duplicate",
            "Match Status", "Match Confidence", "Date Difference Days",
            "Candidates Within 7 Days", "Amazon Order Type", "Amazon Order ID",
            "Amazon Order Date", "Amazon Ship Date", "Amazon Amount",
            "Amazon Item Count", "Amazon Department", "Amazon Product",
            "Recommended Category", "Recommendation Confidence", "Assessment", "Reason");
    private static final Recommendation noRecommendation =
            new Recommendation("", "Low", "Candidate categories need review");

    private AmazonTransactionReview() { }
}
