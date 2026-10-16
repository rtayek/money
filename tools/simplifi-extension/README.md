# Simplifi Transaction Probe

This unpacked Chrome extension inspects and matches Quicken Simplifi transactions
against an annotated CSV. It can prepare a note for review, or save notes in an
explicitly confirmed batch of up to five visible exact matches. It does not
change categories, store the CSV, or make network requests.

## Install

1. Open `chrome://extensions` in Chrome.
2. Turn on **Developer mode**.
3. Choose **Load unpacked** and select this `tools/simplifi-extension` directory.
4. Reload the open Simplifi tab. After updating the extension, reload it on the
   extensions page and reload Simplifi too.

## Inspect and match

1. Sign in to Simplifi normally and open **Transactions**.
2. Filter Transactions to one payee such as **Google**.
3. Open the extension and choose `all-annotated.csv`.
4. Enter the same payee filter and choose **Match visible rows**.
5. Review the totals and row outlines: green is exact, yellow is probable
   (account mismatch or unknown transaction direction), and red is ambiguous.

Matching requires date, payee, amount, and account. Simplifi CSV amounts use
negative values for charges and positive values for credits. An exact match
requires the page amount to explicitly show its sign (`-`, `+`, or negative
parentheses). An unsigned page amount is only probable; it cannot authorize
note preparation or a batch save. Opposite signed amounts never match. Missing
or invalid amounts never match. Duplicate claims are ambiguous.

Choose **Sweep filtered rows** to read the entire filtered, virtualized grid.
Simplifi scrolls temporarily while the extension collects transaction IDs and
named cells, then returns to the original scroll position. Do not interact with
the grid during the sweep. The sweep stops after 30 seconds and reports whether
it completed, including missing CSV targets and unmatched Simplifi rows.
Outlines follow transaction IDs as rows are virtualized.

**Scan visible page** produces a diagnostic report masking free-form text,
dates, amounts, email addresses, and long numbers while retaining DOM structure,
grid column names, and common transaction labels. **Inspect open transaction**
reports editor labels and controls without field values or changes.

## Prepare or save notes

**Prepare open note (not saved)** requires one exact CSV match for the open
editor. It fills the Note field; inspect the result and click Update yourself.

The confirmed batch handles at most five visible exact matches, saves one at a
time, waits for the dialog to close, reopens each transaction to verify its
saved note, and stops on the first unexpected condition. Probable, ambiguous,
and missing matches are skipped.

Notes are one line, as required by Simplifi. Existing text and annotations are
joined with ` | `; imported line breaks are converted to the same separator.
An annotation already present is skipped, including after newline conversion.
The CSV is parsed only in extension memory and is never uploaded or stored.

Version 0.7.4 adds signed matching and one-line notes. Previous 0.7 fixes added
Payee-cell opening (0.7.1), dialog-level Close lookup (0.7.2), and waits for
dialog transitions (0.7.3). Actual Simplifi UI behavior still needs manual
verification after updating.

## Checks

```sh
node tools/simplifi-extension/probe-core.test.js
node tools/simplifi-extension/matcher-core.test.js
```

These deterministic checks cover matching, note merging, and diagnostic masking;
they do not exercise a live browser's open/save/close sequence.
