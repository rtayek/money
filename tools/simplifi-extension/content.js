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

  function scan() {
    const rows = uniqueVisible(rowSelector, 100);
    const controls = uniqueVisible(controlSelector, 150);
    return {
      probeVersion: "0.1.0",
      capturedAt: new Date().toISOString(),
      page: {
        origin: location.origin,
        path: simplifiProbe.sanitizeText(location.pathname),
        title: simplifiProbe.sanitizeText(document.title)
      },
      counts: {
        rowCandidates: rows.length,
        controls: controls.length
      },
      rows: rows.map(simplifiProbe.describeElement),
      controls: controls.map(simplifiProbe.describeElement)
    };
  }

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message && message.type === "simplifi-probe-scan") {
      sendResponse({ ok: true, report: scan() });
    }
  });
})();
