package money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Drops exact duplicates created when one real account is linked into
 * Simplifi twice (e.g. "Traditional Gold Card" and "American Express
 * Traditional Gold" carry the same charges). An account is treated as a
 * mirror when at least {@link #mirrorFraction} of its rows have an exact
 * (date, payee, amount) twin in one other account; the smaller account's
 * mirrored rows are then removed. Duplicates are only collapsed across
 * different accounts, so genuine same-day repeats within one account are
 * kept, and unrelated accounts that share the odd identical charge are left
 * alone. */
final class MirrorDuplicates {

    interface Candidate {
        String account();
        LocalDate date();
        String payee();
        String category();
        double amount();

        default String mirrorKey() {
            return date() + "|" + payee() + "|" + String.format(Locale.US, "%.2f", amount());
        }
    }

    static <T extends Candidate> List<T> remove(List<T> rows) {
        Map<String, List<T>> byAccount = new LinkedHashMap<>();
        for (T r : rows)
            byAccount.computeIfAbsent(r.account(), k -> new ArrayList<>()).add(r);
        Map<String, String> mirrorOf = detectMirrors(byAccount);
        Map<String, Map<String, List<T>>> available = new LinkedHashMap<>();
        for (String partner : new HashSet<>(mirrorOf.values()))
            available.put(partner, rowsByKey(byAccount.get(partner)));
        List<T> out = new ArrayList<>(rows.size());
        int dropped = 0;
        int categoryConflicts = 0;
        for (T r : rows) {
            String partner = mirrorOf.get(r.account());
            if (partner != null) {
                List<T> twins = available.get(partner).get(r.mirrorKey());
                if (twins != null && !twins.isEmpty()) {
                    T kept = twins.remove(twins.size() - 1);
                    if (!r.category().equalsIgnoreCase(kept.category())) {
                        categoryConflicts++;
                        System.out.printf(Locale.US,
                                "Warning: mirrored copies have different categories: "
                                        + "%s | %s | %,.2f | dropping %s [%s], keeping %s [%s]%n",
                                r.date(), r.payee(), r.amount(), r.account(), r.category(),
                                kept.account(), kept.category());
                    }
                    dropped++;
                    continue;
                }
            }
            out.add(r);
        }
        if (dropped > 0)
            System.out.printf(Locale.US, "Ignored %d duplicate rows from mirrored account(s): %s%n", dropped, mirrorOf);
        if (categoryConflicts > 0)
            System.out.printf(Locale.US, "Found %d mirrored category conflict(s); review the warnings above.%n",
                    categoryConflicts);
        return out;
    }

    /** Maps each mirror account to the account whose copy is kept. */
    private static <T extends Candidate> Map<String, String> detectMirrors(Map<String, List<T>> byAccount) {
        Map<String, String> mirrorOf = new LinkedHashMap<>();
        if (byAccount.size() < 2) return mirrorOf;
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (Map.Entry<String, List<T>> e : byAccount.entrySet())
            counts.put(e.getKey(), keyCounts(e.getValue()));
        for (String a : byAccount.keySet()) {
            int size = byAccount.get(a).size();
            if (size < mirrorMinRows) continue;
            String best = null;
            int bestOverlap = 0;
            for (String b : byAccount.keySet()) {
                if (b.equals(a)) continue;
                int ov = overlap(counts.get(a), counts.get(b));
                if (ov > bestOverlap) {
                    bestOverlap = ov;
                    best = b;
                }
            }
            if (best != null && bestOverlap >= mirrorFraction * size) {
                int bSize = byAccount.get(best).size();
                // strip the smaller account (ties: the later-sorting name), keep the other
                if (size < bSize || (size == bSize && a.compareTo(best) > 0))
                    mirrorOf.put(a, best);
            }
        }
        return mirrorOf;
    }

    private static <T extends Candidate> Map<String, List<T>> rowsByKey(List<T> rows) {
        Map<String, List<T>> m = new LinkedHashMap<>();
        for (T r : rows)
            m.computeIfAbsent(r.mirrorKey(), k -> new ArrayList<>()).add(r);
        return m;
    }

    private static Map<String, Integer> keyCounts(List<? extends Candidate> rows) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Candidate r : rows)
            m.merge(r.mirrorKey(), 1, Integer::sum);
        return m;
    }

    /** Count of shared keys between two multisets, respecting multiplicity. */
    private static int overlap(Map<String, Integer> a, Map<String, Integer> b) {
        int total = 0;
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            Integer bc = b.get(e.getKey());
            if (bc != null) total += Math.min(e.getValue(), bc);
        }
        return total;
    }

    private MirrorDuplicates() {}

    private static final int mirrorMinRows = 20;
    private static final double mirrorFraction = 0.90;
}
