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

Only the visible DOM is scanned. Virtualized rows outside the viewport are not
included. Version 0.3 can compare an annotated export with the visible rows.
Editing stays disabled until read-only matching has been tested against real
Simplifi pages.

## Match an annotated export

1. In Simplifi, filter Transactions to one payee such as **Google**.
2. Open the extension and choose `all-annotated.csv`.
3. Enter the same payee filter and choose **Match visible rows**.
4. Review the totals and row outlines: green is exact, yellow is a
   unique date/payee/amount match with an account-name mismatch, and red is
   ambiguous.

The CSV is parsed in memory inside the extension and is never uploaded or
stored. Matching uses date, account, payee, and absolute amount. The extension
does not write notes or click Simplifi controls in version 0.3.
Outlines are tied to transaction IDs, so scrolling cannot transfer a mark to a
different virtualized row.

Run the deterministic sanitizer checks with:

```sh
node tools/simplifi-extension/probe-core.test.js
node tools/simplifi-extension/matcher-core.test.js
```
