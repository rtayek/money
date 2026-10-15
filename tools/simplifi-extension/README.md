# Simplifi Transaction Probe

This unpacked Chrome extension inspects the visible Quicken Simplifi page. It
does not edit transactions, store data, or make network requests.
Its diagnostic report masks free-form text, dates, amounts, email addresses,
and long numbers while retaining DOM structure, grid column names, and common
transaction labels.

## Install

1. Open `chrome://extensions` in Chrome.
2. Turn on **Developer mode**.
3. Choose **Load unpacked**.
4. Select this `tools/simplifi-extension` directory.
5. Reload the open Simplifi tab.

## Probe the transaction page

1. Sign in to Simplifi normally and open **Transactions**.
2. Click the extension icon and choose **Scan visible page**.
3. Confirm that it found row candidates and controls.
4. Use **Copy report** when the adapter needs diagnosis.

With a transaction details panel open, **Inspect open transaction** produces a
small read-only report of that editor's labels and controls. It never includes
field values and does not click, type, or submit anything.

Version 0.6 adds a controlled **Prepare open note (not saved)** pilot. After an
annotated CSV is loaded and one transaction editor is open, it requires exactly
one date/account/payee/amount match, preserves or appends to an existing Note,
and fills the Note field. It never clicks **Update**; the human must inspect the
field and decide whether to save or close the editor.

Version 0.7 adds an explicitly confirmed batch limited to five visible exact
matches. It preserves existing Notes, skips annotations already present, saves
one transaction at a time, reopens each saved transaction to verify the full
annotation, and stops on the first unexpected UI or matching condition. It
does not change categories or process probable, ambiguous, or missing matches.

The basic scan only reads the visible DOM. Version 0.4 can also sweep through a
filtered, virtualized transaction grid to compare an annotated export with all
of the rows in that filtered view. Editing stays disabled until read-only
matching has been tested against real Simplifi pages.

## Match an annotated export

1. In Simplifi, filter Transactions to one payee such as **Google**.
2. Open the extension and choose `all-annotated.csv`.
3. Enter the same payee filter and choose **Match visible rows**.
4. Review the totals and row outlines: green is exact, yellow is a
   unique date/payee/amount match with an account-name mismatch, and red is
   ambiguous.

Choose **Sweep filtered rows** to read the entire filtered transaction grid.
Simplifi scrolls temporarily while the extension collects transaction IDs and
named cells, then returns to the original scroll position. Do not interact with
the transaction grid during the sweep. The sweep stops after 30 seconds rather
than continuing indefinitely and reports whether it completed. Version 0.4.2
also lists identifying fields for missing CSV targets and unmatched Simplifi
rows so they can be reviewed without hunting through the grid.

The CSV is parsed in memory inside the extension and is never uploaded or
stored. Matching uses date, account, payee, and absolute amount. The extension
does not write notes or click Simplifi controls in version 0.4.
Outlines are tied to transaction IDs, so scrolling cannot transfer a mark to a
different virtualized row.

Run the deterministic sanitizer checks with:

```sh
node tools/simplifi-extension/probe-core.test.js
node tools/simplifi-extension/matcher-core.test.js
```
