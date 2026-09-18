# Matching & Deduplication Patterns

Reusable techniques this project has landed on for reconciling Simplifi's
`all.csv` against a Plaid-derived feed. Durable knowledge -- read this to
understand how the matching works, not what to do next (see
`.llm/working-context.md` for that).

## Cross-source sign convention

Simplifi records an outflow as negative; Plaid's Transactions API records the
same real-world charge as positive. Code comparing an amount from `all.csv`
to the corresponding Plaid amount must check for **opposite signs**, not
equal signs, to confirm "same real transaction."

Currently implemented as `sameTransactionDirection` in both
`AmexMatchReport.java` and `CategorizationAudit.java` -- duplicated, not
shared. Worth pulling into one place before it drifts.

## Cross-account mirror detection (duplicate-linked accounts)

Simplifi can have the same physical card linked twice under two different
account names (seen here as "American Express (r) Traditional Gold" and
"Traditional Gold Card"), double-booking every charge.

Detection rule: for each account with >= 20 rows, find the other account with
the most exact (date, payee, amount) twins. If that overlap is >= 90% of the
smaller account's row count, treat the smaller as a mirror and drop its rows,
keeping the larger.

Implemented in `SpendingPlot.withoutMirroredDuplicates`. Confirmed dropping
130 duplicate rows on real data. Not yet applied in `AmexMatchReport`, which
likely understates its match rate on Amex data as a result -- each duplicate
row competes for the same one real Plaid row, so roughly half will show as
"unmatched" even when the real charge did match.

## One-to-one matching between two transaction feeds

When matching rows between two independently-sourced CSVs of the same
transactions, match greedily but track which candidate rows are already
claimed (a `boolean[] used` array), so one source row can't become the
"match" for more than one target row. Without this, near-duplicate charges
(e.g. the same subscription a month apart) can double-match each other.

## Merchant-name cleaning for cross-source comparison

Bank/card-network merchant strings carry a processor prefix (e.g. `TST*`,
`SQ*`) and a trailing reference blob (e.g. `AMAZON DIGIT*6693T8P888`).
`CategorizationAudit.cleanPayee` strips a 2-5 letter prefix followed by `*`,
then takes the leading run of letters/digits/`&`/`'`/`-` as the merchant
identity -- e.g. both "Amazon" (Simplifi) and "AMAZON DIGIT*6693T8P888"
(Plaid) clean to "AMAZON".

Known gap: PayPal-rail payees ("PAYPAL *SOMEMERCHANT") aren't reliably
cleanable this way, since PayPal is sometimes the real merchant and sometimes
a pass-through for a different one. Deliberately deferred as a hard case, not
a quick regex fix.

## Ambiguous matches get flagged, not silently resolved

When two candidate rows are equally good (same date-distance, same
merchant-relatedness), don't pick the first one arbitrarily -- mark the
match "ambiguous" in the output and count it separately from clean matches.
Both `AmexMatchReport` and `CategorizationAudit` do this now.
