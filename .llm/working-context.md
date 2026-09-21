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

- Apply `SpendingPlot`'s mirror-dedup to `AmexMatchReport` and
  `CategorizationAudit` too, so their match-rate isn't distorted by the
  still-unresolved duplicate Amex account.
- Pull `sameTransactionDirection` out of both matchers into one shared spot.
- Pursue GitHub Support to purge the dangling `copy-of-action-checklist.md`
  commit (`bab6815`): the `money` repo is PUBLIC and that unreachable commit
  is still fetchable by SHA. Confirm no forks first (a fork keeps it alive).
- `human.md` (plain ASCII everywhere) and `persona.md` (em-dashes in prose)
  disagree on Markdown punctuation, now that both are real files -- needs
  an explicit call, not another guess.

## Decisions

- `AGENTS.md`/`human.md`/`persona.md` materialized from symlinks into real
  files, confirmed 2026-09-21 (`git ls-tree` shows zero symlink-mode
  entries anywhere in the repo; all three have real, substantial content).

- `UncategorizedCheckReport.java` deleted 2026-09-21 -- confirmed redundant
  with `SpendingPlot`'s own `findUncategorizedChecks` /
  `writeUncategorizedChecks` / `printUncategorizedChecks`, which already
  covered everything it did.
- AI/software subscriptions (ChatGPT, OpenAI, OpenRouter, Quicken,
  Claude/Anthropic, GitHub, Coursera, AI Sensei) categorize to
  `Utilities:Internet & Cable`. Ray confirmed this on 2026-09-16.
- The recurring $19.99/month "Google" charge is **Google AI Pro (5 TB)**
  (package `com.google.android.apps.subscriptions.red`), confirmed from
  Ray's Play Store order history on 2026-09-19. Same subscription
  previously billed as "100 GB" at $18.99, then $19.99 -- rebranded/
  repriced over time, not separate charges.
- Other recurring Google amounts, identified 2026-09-21 (a Gemini chat's
  guesses, cross-checked against Google's own published pricing -- not all
  of them held up):
  - $1.99 = Google One 100GB Basic tier. Confirmed against official pricing.
  - $2.99 = Google One 200GB Standard tier. Confirmed against official
    pricing -- overrides Gemini's own guess of "games/app purchases" for
    this amount, which official pricing doesn't support.
  - $13.99 / $15.99 = YouTube Premium individual plan (price increased over
    time, explaining both amounts). Plausible, not independently confirmed
    the way the two above are.
  - $3.99 / $5.99 = still unidentified. No Google One or YouTube tier
    matches either price. Ray's own Play Store order history only shows
    these amounts as one-time 2021 purchases from a game ("Premium Land"),
    which doesn't explain a recurring monthly charge if that's what's in
    `all.csv`.
- Google charge categorization, decided 2026-09-21: $19.99 (AI Pro), $1.99
  and $2.99 (Google One storage tiers), and $13.99/$15.99 (YouTube Premium)
  all categorize to `Utilities:Internet & Cable` for now, same bucket as
  the AI-subscription decision above -- a deliberate simplification, not a
  claim these all belong there conceptually (YouTube Premium in particular
  is entertainment, not internet service). Revisit if it matters later.
  $3.99/$5.99 still have nothing to categorize until identified.

## Open questions

- (none currently open)

## Deferred

- Amazon item-level categorization -- blocked on Ray getting
  `amazon-order-history.csv` from Amazon.
- Extending the Google fixed-amount split across the full ~20-month history
  (currently confirmed only for the ~3-month Plaid-covered window) --
  proposed, not applied, pending an explicit go-ahead.
- PayPal-rail payee cleaning -- correctly deferred as a hard case.# Working Context

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

- Apply `SpendingPlot`'s mirror-dedup to `AmexMatchReport` and
  `CategorizationAudit` too, so their match-rate isn't distorted by the
  still-unresolved duplicate Amex account.
- Pull `sameTransactionDirection` out of both matchers into one shared spot.
- Pursue GitHub Support to purge the dangling `copy-of-action-checklist.md`
  commit (`bab6815`): the `money` repo is PUBLIC and that unreachable commit
  is still fetchable by SHA. Confirm no forks first (a fork keeps it alive).
- `AGENTS.md`/`human.md`/`persona.md` are being materialized from symlinks
  into real files (someone else's task, in progress). Done means zero
  symlinks left anywhere in the repo -- worth an explicit check
  (`find . -type l`) once it's done, not just eyeballing it. Once real
  files exist: `human.md` (plain ASCII everywhere) and `persona.md`
  (em-dashes in prose) still disagree on Markdown punctuation -- needs an
  explicit call, not another guess.

## Decisions

- `UncategorizedCheckReport.java` deleted 2026-09-21 -- confirmed redundant
  with `SpendingPlot`'s own `findUncategorizedChecks` /
  `writeUncategorizedChecks` / `printUncategorizedChecks`, which already
  covered everything it did.
- AI/software subscriptions (ChatGPT, OpenAI, OpenRouter, Quicken,
  Claude/Anthropic, GitHub, Coursera, AI Sensei) categorize to
  `Utilities:Internet & Cable`. Ray confirmed this on 2026-09-16.
- The recurring $19.99/month "Google" charge is **Google AI Pro (5 TB)**
  (package `com.google.android.apps.subscriptions.red`), confirmed from
  Ray's Play Store order history on 2026-09-19. Same subscription
  previously billed as "100 GB" at $18.99, then $19.99 -- rebranded/
  repriced over time, not separate charges.
- Other recurring Google amounts, identified 2026-09-21 (a Gemini chat's
  guesses, cross-checked against Google's own published pricing -- not all
  of them held up):
  - $1.99 = Google One 100GB Basic tier. Confirmed against official pricing.
  - $2.99 = Google One 200GB Standard tier. Confirmed against official
    pricing -- overrides Gemini's own guess of "games/app purchases" for
    this amount, which official pricing doesn't support.
  - $13.99 / $15.99 = YouTube Premium individual plan (price increased over
    time, explaining both amounts). Plausible, not independently confirmed
    the way the two above are.
  - $3.99 / $5.99 = still unidentified. No Google One or YouTube tier
    matches either price. Ray's own Play Store order history only shows
    these amounts as one-time 2021 purchases from a game ("Premium Land"),
    which doesn't explain a recurring monthly charge if that's what's in
    `all.csv`.

## Open questions

- (none currently open)

## Deferred

- Amazon item-level categorization -- blocked on Ray getting
  `amazon-order-history.csv` from Amazon.
- Extending the Google fixed-amount split across the full ~20-month history
  (currently confirmed only for the ~3-month Plaid-covered window) --
  proposed, not applied, pending an explicit go-ahead.
- PayPal-rail payee cleaning -- correctly deferred as a hard case.