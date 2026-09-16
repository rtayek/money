# Handoff: Review recurring charges in Simplifi data

## Goal

Find Ray's recurring charges in the Quicken Simplifi transaction export and determine whether each charge is categorized correctly, inconsistently, or not categorized at all.

This is primarily a data-review task. Do not modify the original CSV or automatically change categories.

## Project and input

- Repository: `rtayek/money`
- Work from the local repository checkout used by Anti-Gravity.
- Input file: `all.csv` in the repository root.
- `all.csv` is intentionally gitignored because it contains private financial information. It is not available from GitHub.
- Expected columns: `Date`, `Account`, `Reviewed`, `Payee`, `Category`, `Exclusion`, and `Amount`.
- If the full `all.csv` is absent, stop and ask Ray to export it from Simplifi and place it in the repository root. Do not ask him to commit it.

## Privacy and safety

- Treat `all.csv` and every generated transaction-level report as private.
- Never commit or push the CSV, account data, or generated reports.
- Put generated files under `build/reports/`, which is already ignored.
- Do not expose full account numbers or other sensitive identifiers in summaries.
- Read the current project guidance and recent Git history before changing source code.

## What counts as recurring

Analyze outflows only. Normally exclude:

- rows with `Exclusion` set to `yes`;
- credit-card payments and account-to-account transfers;
- positive amounts, refunds, reimbursements, and income;
- paper checks, ATM withdrawals, and cash transactions unless the evidence clearly shows a repeating bill.

Normalize obvious payee variations cautiously. Preserve the original payee names in the report so Ray can verify every grouping. Do not merge unrelated merchants merely because their names share a word.

Look for these patterns:

- weekly: roughly 6–8 days apart;
- every two weeks: roughly 12–16 days apart;
- monthly: roughly 25–35 days apart, allowing an occasional missing month;
- quarterly: roughly 80–100 days apart;
- semiannual: roughly 170–195 days apart;
- annual: roughly 330–400 days apart.

Usually require at least three occurrences. Two occurrences may be enough for an annual charge because the file contains about two years of data.

Separate fixed subscriptions from variable recurring bills:

- Fixed subscription: amounts are identical or nearly identical.
- Variable recurring bill: timing is regular but amounts vary, such as electricity, telephone, insurance, or utilities.
- Repeated merchant, not necessarily recurring: frequent discretionary purchases such as groceries, restaurants, gasoline, Amazon, or pharmacies. List these separately rather than calling them subscriptions.

Use dates, amount consistency, number of occurrences, and gaps between transactions. Do not rely only on identical payee text.

## Category review

For every likely recurring charge:

1. Show its current Simplifi category or categories.
2. Flag `Uncategorized`, `(Uncategorized)`, blank, or missing categories.
3. Flag the same normalized payee appearing under multiple categories.
4. Flag generic or suspicious categories when a more specific category is reasonably clear.
5. Recommend a category only when the merchant and transaction pattern provide enough evidence.
6. Mark uncertain recommendations as `Needs Ray's review`; do not guess.
7. Identify anything that appears to be a credit-card payment or transfer but was not excluded.

Use the existing category vocabulary in the CSV when possible. Do not invent a new category hierarchy unless the existing categories are clearly inadequate.

## Required output

Create these private, generated files:

- `build/reports/recurring-charges-review.csv`
- `build/reports/recurring-charges-summary.md`

The CSV should contain at least:

- normalized payee;
- original payee variants;
- classification (`Fixed subscription`, `Variable recurring bill`, `Possible recurring`, or `Repeated discretionary merchant`);
- estimated frequency;
- occurrence count;
- first and last dates;
- average, minimum, maximum, and total amount;
- current category or categories;
- recommended category;
- assessment (`Correct`, `Uncategorized`, `Inconsistent`, `Probably incorrect`, or `Needs review`);
- confidence (`High`, `Medium`, or `Low`);
- short reason.

The Markdown summary should be easy to read and should include:

1. high-confidence recurring charges;
2. uncategorized recurring charges;
3. inconsistent or probably incorrect categories;
4. possible credit-card payments or transfers still counted as spending;
5. uncertain cases requiring Ray's decision;
6. estimated monthly and annual recurring totals, with fixed subscriptions and variable bills shown separately.

Do not paste hundreds of raw transactions into the summary. Include enough dates, amounts, and payee variants to support each conclusion.

## Verification

- Report the number of CSV rows read and the date range.
- Reconcile the candidate groups back to their source transactions.
- Confirm excluded rows and positive amounts did not enter recurring-spending totals.
- Spot-check monthly, quarterly, and annual candidates.
- Check that no generated report is tracked by Git.
- State the detection rules and important ambiguities in the Markdown summary.

## Definition of done

The task is complete when Ray has a concise list of recurring charges, their estimated monthly and annual cost, and a clear review of which categories are correct, missing, inconsistent, questionable, or require his judgment. Report findings first. Do not change Simplifi or the source CSV.
