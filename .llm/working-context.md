# Working Context

## Current state

Java tools in `src/money/` read Simplifi's `all.csv` and, optionally, a
Plaid-derived CSV or Amazon's own order-history export, to find and fix
categorization problems. Durable technical patterns (sign conventions, the
mirror-duplicate algorithm, one-to-one matching, merchant-name cleaning)
live in `doc/patterns.md` -- read it for any task touching `SpendingPlot`,
`AmexMatchReport`, `CategorizationAudit`, `AmazonTransactionReview`, or
`scripts/simplifi-diff.r`.

- `SpendingPlot` -- the main charting tool. Auto-detects and drops
  cross-account mirror duplicates (130 duplicate rows from the double-linked
  Amex account pair on real data). Also handles charge/reversal pairs
  (matched by account, opposite amount, 3-day window, and wording like
  "adjustment"/"refund"/"reversed") and merchant refunds (Amazon, Temu,
  Walmart credits count as negative Shopping spending, not income, so a
  refund offsets the original purchase instead of inflating income).
  Writes `reports/uncategorized-checks.csv`, `reports/recurring-
  candidates.csv`, and `reports/standard-deviations.csv`.
  - Deviation classification (High/Low/Omitted), built together
    2026-09-28: every category is checked in order -- excluded-by-name,
    then average below $25 (Omitted, unless a single month spiked past
    $300, which promotes it to High instead), then relative std dev below
    10% of average (Omitted), then genuinely flat (std dev under $50 and
    max deviation under $350 -- Low, checked before magnitude ever gets a
    say), then real High criteria (std dev >= $150, max deviation >= $350,
    or >= 200% of average), else Low. Every category prints one line
    showing its bucket and why -- nothing is silently dropped.
  - The Std Dev tab shows a Status column and color-codes every row by
    bucket (solid red/blue/gray, not pale tints -- pale tints turned out
    to be invisible given Ray's low vision). All numeric columns are
    sortable by clicking the header (values are stored as raw numbers
    under the hood so sorting is numeric, not alphabetical).
  - Categories default to detailed (with subcategories); `--main-
    categories` collapses them.
  - New "Income & Spending" cash-flow tab, derived from `all.csv`'s own
    "Personal Income" categories -- no separate income file needed despite
    an unrelated `/income.csv` gitignore entry that isn't actually read by
    any code.
- `AmazonTransactionReview` -- new 2026-09-28. Matches Simplifi's Amazon
  charges against Amazon's own order-history export (`private/amazon/Your
  Orders.zip`, both "Order History.csv" and "Digital Content Orders.csv"
  inside it) by amount and date proximity (within 7 days), and recommends
  a specific category per charge (known subscriptions like Kindle
  Unlimited/Audible by name; otherwise by Amazon's own department
  taxonomy). Reuses the same mirror-dedup as SpendingPlot. Writes
  `reports/amazon-transaction-review.csv` (everything) and `reports/
  amazon-action-items.csv` (just the ones whose current category doesn't
  match the recommendation). Verified end-to-end by Claude against a
  synthetic test ZIP (correctly matched and categorized both a retail and
  a digital order) -- not yet verified against Ray's actual export file,
  which Claude does not have access to. `scripts/last-60-amazon.sh` prints
  the last 60 days of action items via R, sorted newest first.
- `AmexMatchReport` and `CategorizationAudit` -- match Simplifi rows to
  Plaid rows: one-to-one, sign-aware, merchant-token-based. Still do NOT
  have SpendingPlot's mirror-dedup applied (see Next).
- `tools/plaid/PullTransactions.java` -- archived source, builds/runs in a
  separate Plaid Quickstart clone. Tags each row with an account label,
  pulls up to the 2-year max history (set at Link time, not pull time).
- `scripts/simplifi-diff.r` -- compares two Simplifi CSV exports (before/
  after) and reports what changed; useful for verifying bulk edits made
  directly in Simplifi.
- `project-home.html` -- a personal, high-contrast (large font, dark mode)
  link page for the project, matching Ray's low-vision needs. Not
  reviewed in depth; no known issues.
- `.llm/human.md` and `.llm/persona.md` were fully retired (deleted) from
  this repo 2026-09-28 -- they no longer exist here at all. Superseded the
  earlier "materialized from symlinks" state.

## Next

- Apply `SpendingPlot`'s mirror-dedup to `AmexMatchReport` and
  `CategorizationAudit` too, so their match-rate isn't distorted by the
  still-unresolved duplicate Amex account.
- Pull `sameTransactionDirection` out of both matchers into one shared spot.
- Pursue GitHub Support to purge the dangling `copy-of-action-checklist.md`
  commit (`bab6815`): the `money` repo is PUBLIC and that unreachable commit
  is still fetchable by SHA. Confirm no forks first (a fork keeps it alive).
- Verify `AmazonTransactionReview` against Ray's actual `private/amazon/
  Your Orders.zip` -- only synthetic-data-tested so far.

## Decisions

- `human.md`/`persona.md` retired from the `money` repo entirely,
  2026-09-28 -- no longer materialized here at all. This supersedes the
  earlier "materialized from symlinks into real files" decision, and
  retires the ASCII-vs-em-dash disagreement between them as moot.
- `UncategorizedCheckReport.java` deleted 2026-09-21 -- confirmed redundant
  with `SpendingPlot`'s own equivalents.
- AI/software subscriptions (ChatGPT, OpenAI, OpenRouter, Quicken,
  Claude/Anthropic, GitHub, Coursera, AI Sensei) categorize to
  `Utilities:Internet & Cable`. Ray confirmed this on 2026-09-16.
- The recurring $19.99/month "Google" charge is **Google AI Pro (5 TB)**
  (package `com.google.android.apps.subscriptions.red`), confirmed from
  Ray's Play Store order history on 2026-09-19. Same subscription
  previously billed as "100 GB" at $18.99, then $19.99.
- Other recurring Google amounts, identified 2026-09-21 (a Gemini chat's
  guesses, cross-checked against Google's own published pricing):
  - $1.99 = Google One 100GB Basic tier. Confirmed.
  - $2.99 = Google One 200GB Standard tier. Confirmed -- overrides
    Gemini's own "games/app purchases" guess.
  - $13.99 / $15.99 = YouTube Premium individual plan (price increased
    over time). Plausible, not independently confirmed.
  - $3.99 / $5.99 = still unidentified.
- Google charge categorization, decided 2026-09-21: $19.99, $1.99, $2.99,
  $13.99, and $15.99 all categorize to `Utilities:Internet & Cable` for
  now -- a deliberate simplification, not a claim these all belong there
  conceptually (YouTube Premium in particular is entertainment). $3.99/
  $5.99 still have nothing to categorize until identified.
- `index.md` is the shared dotmdfiles template and stays identical across
  all of Ray's projects -- project-specific pointers belong here in
  `working-context.md` instead. Ray confirmed this 2026-09-21.
- Amazon item-level categorization: no longer blocked. Ray obtained his
  order-history export and `AmazonTransactionReview` (see Current state)
  now does the matching. Originally deferred pending the export; that
  block is resolved as of 2026-09-28.

## Open questions

- A new rule (added outside this chat, 2026-09-28) omits any category
  whose relative std dev is under 10% of its own average, checked before
  the "steady -> Low" rule Ray and Claude built together. Net effect:
  Mortgage and Health Insurance -- both very large, very steady -- now
  land in Omitted instead of Low, disappearing from both deviation charts
  entirely. Asked Ray whether he wants them back in Low (visible, just
  correctly labeled boring) or is fine with the new Omitted behavior.
  Not yet answered.

## Deferred

- Own the Plaid data as a real database instead of an overwritten CSV
  snapshot -- Simplifi doesn't retain the raw merchant descriptor once it
  cleans a payee name, so the Plaid pull is the only place that detail
  exists at all. Proposed: SQLite, keyed on Plaid's stable
  `transaction_id` so repeated pulls accumulate/refresh instead of
  overwriting. Ray confirmed 2026-09-21 this goes on the list, not done
  now. Still open when picked back up: does `PullTransactions` write
  straight to SQLite or keep writing CSV with a separate import step, and
  do `CategorizationAudit`/`AmexMatchReport` switch to reading the
  database or stay on CSV with the database as just the archive.
- Extending the Google fixed-amount split across the full ~20-month history
  (currently confirmed only for the ~3-month Plaid-covered window).
- PayPal-rail payee cleaning -- correctly deferred as a hard case.
