(function installContentProbe() {
  const rowSelector = [
    "[role='row']",
    "tr",
    "[data-testid*='transaction' i]",
    "[class*='transaction' i]"
  ].join(",");

  const controlSelector = [
    "button",
    "input",
    "select",
    "textarea",
    "[role='button']",
    "[role='searchbox']",
    "[contenteditable='true']"
  ].join(",");

  function isVisible(element) {
    const style = getComputedStyle(element);
    const bounds = element.getBoundingClientRect();
    return style.display !== "none" && style.visibility !== "hidden" &&
      bounds.width > 0 && bounds.height > 0;
  }

  function uniqueVisible(selector, limit) {
    return Array.from(new Set(document.querySelectorAll(selector)))
      .filter(isVisible)
      .slice(0, limit);
  }

  function cellsIn(element) {
    return Array.from(element.querySelectorAll("[role='gridcell'], [role='cell'], [role='columnheader'], [data-field]"))
      .filter(isVisible)
      .map(simplifiProbe.describeCell);
  }

  function describeRow(row) {
    return {
      ...simplifiProbe.describeElement(row),
      rowIndex: row.getAttribute("data-rowindex") || "",
      hasDataId: row.hasAttribute("data-id"),
      cells: cellsIn(row)
    };
  }

  function scan() {
    const rows = uniqueVisible(rowSelector, 100);
    const controls = uniqueVisible(controlSelector, 150);
    const headers = uniqueVisible("[role='columnheader']", 50);
    return {
      probeVersion: "0.2.0",
      capturedAt: new Date().toISOString(),
      page: {
        origin: location.origin,
        path: simplifiProbe.sanitizeText(location.pathname),
        title: simplifiProbe.sanitizeText(document.title)
      },
      counts: {
        rowCandidates: rows.length,
        controls: controls.length,
        columnHeaders: headers.length
      },
      headers: headers.map(simplifiProbe.describeCell),
      rows: rows.map(describeRow),
      controls: controls.map(simplifiProbe.describeElement)
    };
  }

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message && message.type === "simplifi-probe-scan") {
      sendResponse({ ok: true, report: scan() });
    }
  });
})();
