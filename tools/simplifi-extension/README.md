# Simplifi Transaction Probe

This unpacked Chrome extension inspects the visible Quicken Simplifi page. It
does not edit transactions, read files, store data, or make network requests.
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
included. Version 0.2 reports the sanitized column structure within each visible
row. The next stage will use the resulting structure to match exported
transactions. Editing stays disabled until read-only matching has been tested
against real Simplifi pages.

Run the deterministic sanitizer checks with:

```sh
node tools/simplifi-extension/probe-core.test.js
```
