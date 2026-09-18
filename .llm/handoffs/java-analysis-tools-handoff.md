# Handoff: Java tools for recurring analysis and Plaid combining

Two Java tools to add to the `money` project (Eclipse, module `money`). Ray
does not use Python, so these must be Java (or JShell) - not Python scripts.
Earlier throwaway Python produced the reports described here and was discarded;
the goal now is durable, repeatable Java that Ray can run and maintain.

## Ground rules (from AGENTS.md and .llm/human.md)

- Java only, in `src/money/`, module `money` (currently `requires java.desktop`).
- Plain ASCII, LF line endings. No `_` in names (use `-` in files, camelCase in code).
- Minimal comments (code is the documentation). Fields at the bottom of the class.
- Read inputs from the repo root; write generated reports under `build/reports/`
  (git-ignored). Never commit private data (`all.csv`, `plaid-transactions.csv`,
  `tmp/`, `build/reports/`, `card-registry.md`, `payee-match-misses.md`).
- Reuse the mirror-duplicate logic already in `SpendingPlot.loadAllTransactions`
  / `withoutMirroredDuplicates` rather than reinventing it.

## Existing pieces to build on

- `src/money/SpendingPlot.java` - CSV load, spending model, and the
  cross-account mirror-dedupe (an account counts as a mirror when >= 90% of its
  rows have an exact date+payee+amount twin in one other account; only
  cross-account duplicates are dropped).
- `src/money/CategorizationAudit.java` - dual-format matcher (Simplifi all.csv
  vs Plaid feed). Matching is one-to-one and requires merchant token, amount,
  opposite source-specific signs, and dates within two days; tied candidates
  are reported as ambiguous rather than clean passes.
- `src/money/AmexMatchReport.java` - matches Simplifi Amex rows to a Plaid feed.
- `tools/plaid/PullTransactions.java` - archived copy; builds/runs in the Plaid
  quickstart project. Emits `Date,AccountId,Account,Name,MerchantName,`
  `OriginalDescription,Category,Amount` (Account = "name (mask)").

## Task 5: recurring-charges analysis + category recommendations (Java)

Reproduce, in Java, what the discarded Python did. Input: `all.csv` (Simplifi
export), optional `amazon-order-history.csv`. Output to `build/reports/`.

Behavior to reproduce:
- Analyze outflows only; exclude Simplifi-excluded rows, transfers/card
  payments, positive amounts. Reuse the mirror-dedupe so the double-linked gold
  account is not counted twice.
- Group by a cautiously normalized payee (keep original variants), detect
  frequency (weekly..annual) by median gap, require >= 3 occurrences (2 for
  annual). Classify: fixed subscription, variable recurring bill, possible
  recurring, repeated discretionary merchant.
- For bundling payees (Google, Amazon, PayPal, Apple), cluster by exact amount
  to surface hidden subscriptions; flag when one amount is split across
  categories. Do not guess the specific service.
- Category review per charge: flag Uncategorized/blank, same payee under
  multiple categories (Inconsistent), implausible categories (e.g. In-N-Out
  filed as Travel:Hotel), and recommend a category only with clear evidence;
  otherwise "Needs Ray's review".
- Emit two files: `build/reports/recurring-charges-review.csv` (per-charge
  detail) and a readable `recurring-charges-summary.md`, plus a
  `recommended-category-changes.csv` (per-transaction fixes: date, account,
  payee, amount, current -> recommended, confidence, reason).
- Full spec: `.llm/handoffs/recurring-charges-analysis-handoff.md`.

Decisions already made with Ray (bake in as defaults, still let him override):
- AI/software subscriptions (ChatGPT, Claude/Anthropic, GitHub, Coursera,
  AI Sensei, OpenAI, OpenRouter) consolidate to `Utilities:Internet & Cable`.
- Google splits into `Google One` -> Utilities:Internet & Cable and Google Play
  games (Merge Dragons, Pyramid Solitaire, ...) -> Entertainment.

## Task 6: Plaid combiner (Java)

A tool that reads multiple per-login pull files under `tmp/plaid/` (new schema
with `Account`/mask), and writes a single deduped `tmp/plaid/combined.csv`.

- Auto-detect shared accounts: the same real account seen from two logins shows
  a different mask but shares many exact transactions (date+amount+
  OriginalDescription). Reuse the same >= 90%-overlap mirror rule.
- Dedupe only across the detected shared pair, preserving within-account
  repeats and multiplicity (keep one login's copy as authoritative). Never
  dedupe unrelated accounts that share the odd identical charge.
- Print what it detected and how many rows it dropped.
- `card-registry.md` documents the owner/login/card/mask/Simplifi mapping and a
  Shared tag; treat it as documentation, but detection should be automatic so
  the tool does not depend on the registry being correct.

Known reference numbers (as of 2026-09-17, ~3 months of Plaid history):
- ray amex login: Traditional Gold `2002` (94 rows).
- laurie amex login: Delta SkyMiles `4001` (134) + Traditional Gold `1012` (71).
- `2002` and `1012` are the same shared account (70 identical transactions);
  combined deduped total = 229.

## Verification

- Report rows read and date range; reconcile group counts to source rows.
- Confirm excluded rows, positive amounts, and mirrored duplicates never enter
  totals.
- Confirm no generated report is tracked by Git.
- State detection rules and thresholds in the summary output.

## Related

- Data-quality logs: `payee-match-misses.md`, `card-registry.md` (both local,
  git-ignored).
- Mirror-dedupe precedent: `SpendingPlot.withoutMirroredDuplicates`.
