# Working Context

## Current state

Java tools in `src/money/` read Simplifi's `all.csv` and, optionally, a
Plaid-derived CSV to find and fix categorization problems. The Plaid puller
itself (`tools/plaid/PullTransactions.java`) is archived source only -- it
builds and runs inside a separate Plaid Quickstart clone (Maven, Plaid SDK),
not in this repo's build.

- `SpendingPlot` -- the main charting tool. Auto-detects and drops
  cross-account mirror duplicates (see `docs/patterns.md`); confirmed
  dropping 130 duplicate rows from the double-linked Amex account pair on
  real data.
- `AmexMatchReport` and `CategorizationAudit` -- match Simplifi rows to Plaid
  rows: one-to-one, sign-aware, merchant-token-based. Both compile and run
  correctly against real data.
- `tools/plaid/PullTransactions.java` -- tags each row with an account
  label, supports multiple accounts per login, pulls up to the 2-year max
  history (set at Link time, not at pull time).

## Next

- Apply `SpendingPlot`'s mirror-dedup to `AmexMatchReport` too, so its
  match-rate isn't distorted by the still-unresolved duplicate Amex account.
- Decide, for real: delete `UncategorizedCheckReport.java`, or keep it. It's
  been re-added three times after being asked to delete it twice.
- Pull `sameTransactionDirection` out of both matchers into one shared spot.

## Open questions

- Where should AI subscriptions (ChatGPT, OpenRouter, Quicken,
  Claude/Anthropic) actually be categorized? "Utilities:Internet & Cable"
  was applied without Ray confirming it -- still unsettled.
- Is the `money` GitHub repo public or private? Determines whether the
  dangling `copy-of-action-checklist.md` commit residue is worth pursuing
  with GitHub Support.

## Deferred

- Amazon item-level categorization -- blocked on Ray getting
  `amazon-order-history.csv` from Amazon.
- Extending the Google fixed-amount split across the full ~20-month history
  (currently confirmed only for the ~3-month Plaid-covered window) --
  proposed, not applied, pending an explicit go-ahead.
- PayPal-rail payee cleaning -- correctly deferred as a hard case.
