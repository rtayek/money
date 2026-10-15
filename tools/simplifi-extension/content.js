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

  function associatedLabel(control) {
    const labels = [];
    const labelledBy = (control.getAttribute("aria-labelledby") || "").split(/\s+/).filter(Boolean);
    for (const id of labelledBy) {
      const label = document.getElementById(id);
      if (label) labels.push(label.innerText || label.textContent || "");
    }
    for (const label of Array.from(control.labels || [])) {
      labels.push(label.innerText || label.textContent || "");
    }
    const enclosingLabel = control.closest("label");
    if (enclosingLabel) labels.push(enclosingLabel.innerText || enclosingLabel.textContent || "");

    let ancestor = control.parentElement;
    for (let depth = 0; ancestor && depth < 5; depth++, ancestor = ancestor.parentElement) {
      const label = ancestor.querySelector(":scope > label, :scope > legend, :scope > .MuiFormLabel-root");
      if (label) labels.push(label.innerText || label.textContent || "");
    }
    return simplifiProbe.sanitizeText(Array.from(new Set(labels)).join(" "));
  }

  function describeEditorControl(control, index) {
    return {
      index,
      tag: control.tagName.toLowerCase(),
      role: control.getAttribute("role") || "",
      type: control.getAttribute("type") || "",
      label: associatedLabel(control),
      ariaLabel: simplifiProbe.sanitizeText(control.getAttribute("aria-label")),
      placeholder: simplifiProbe.sanitizeText(control.getAttribute("placeholder")),
      name: simplifiProbe.safeFieldName(control.getAttribute("name")),
      text: simplifiProbe.sanitizeText(control.innerText || ""),
      valueState: "value" in control && control.value ? "nonempty" : "empty",
      readOnly: Boolean(control.readOnly),
      disabled: Boolean(control.disabled),
      classes: simplifiProbe.shortClasses(control.className)
    };
  }

  function inspectOpenEditor() {
    const textareas = uniqueVisible("textarea", 20);
    const roots = textareas.map(textarea => textarea.closest("form")).filter(Boolean);
    const root = roots.find(form => form.querySelector("button[type='submit']")) ||
      textareas[0]?.closest("[role='dialog'], aside, [class*='Drawer']") || null;
    if (!root) throw new Error("Open a transaction details panel first.");
    const controls = Array.from(root.querySelectorAll("input, textarea, select, button"))
      .filter(isVisible);
    return {
      probeVersion: "0.5.0",
      root: {
        tag: root.tagName.toLowerCase(),
        role: root.getAttribute("role") || "",
        classes: simplifiProbe.shortClasses(root.className)
      },
      controlCount: controls.length,
      controls: controls.map(describeEditorControl)
    };
  }

  function cellText(row, field) {
    const cell = row.querySelector(`[role='gridcell'][data-field='${field}']`);
    return cell ? (cell.innerText || cell.textContent || "").trim() : "";
  }

  function visibleTransactions() {
    return uniqueVisible("[role='row'][data-rowindex]", 250)
      .filter(row => row.querySelector("[role='gridcell'][data-field='payee']"))
      .map(row => ({
        rowKey: row.getAttribute("data-id"),
        row,
        date: cellText(row, "date"),
        account: cellText(row, "account"),
        payee: cellText(row, "payee"),
        category: cellText(row, "category"),
        notes: cellText(row, "notes"),
        amount: cellText(row, "amount")
      }));
  }

  function transactionSnapshot(transaction) {
    const { row, ...snapshot } = transaction;
    return snapshot;
  }

  function transactionScroller() {
    const candidates = Array.from(document.querySelectorAll(
      ".MuiDataGrid-virtualScroller, [class*='MuiDataGrid-virtualScroller']"
    )).filter(element => element.clientHeight > 0);
    return candidates.sort((left, right) => right.scrollHeight - left.scrollHeight)[0] || null;
  }

  function waitForGrid() {
    return new Promise(resolve => requestAnimationFrame(() =>
      requestAnimationFrame(() => setTimeout(resolve, 140))));
  }

  async function sweepFiltered(targets) {
    const scroller = transactionScroller();
    if (!scroller) throw new Error("The Simplifi transaction grid was not found.");

    const originalScrollTop = scroller.scrollTop;
    const transactions = new Map();
    const deadline = Date.now() + 30000;
    let scrollSteps = 0;
    let truncated = false;

    function collectVisibleTransactions() {
      for (const transaction of visibleTransactions()) {
        if (transaction.rowKey) transactions.set(transaction.rowKey, transactionSnapshot(transaction));
      }
    }

    try {
      scroller.scrollTop = 0;
      await waitForGrid();

      for (let position = 0; position < 500; position++) {
        collectVisibleTransactions();
        const maximum = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
        const current = scroller.scrollTop;
        if (current >= maximum - 1) break;
        if (Date.now() >= deadline || position === 499) {
          truncated = true;
          break;
        }

        const distance = Math.max(100, Math.floor(scroller.clientHeight * 0.6));
        const next = Math.min(maximum, current + distance);
        scroller.scrollTop = next;
        scroller.dispatchEvent(new Event("scroll", { bubbles: true }));
        await waitForGrid();
        scrollSteps++;
        if (scroller.scrollTop <= current && next > current) {
          truncated = true;
          break;
        }
      }

      collectVisibleTransactions();
      const scannedTransactions = Array.from(transactions.values());
      const results = simplifiMatcher.classifyMatches(targets, scannedTransactions);
      const unmatched = simplifiMatcher.unmatchedDetails(results, scannedTransactions);
      markMatches(results);
      return {
        scannedRows: transactions.size,
        scrollSteps,
        truncated,
        missingTargets: unmatched.missingTargets.map(target => ({
          csvRow: target.csvRow,
          date: target.date,
          account: target.account,
          payee: target.payee,
          amount: target.amount,
          category: target.category || ""
        })),
        unmatchedRows: unmatched.unmatchedRows.map(row => ({
          date: row.date,
          account: row.account,
          payee: row.payee,
          amount: row.amount,
          category: row.category
        })),
        counts: Object.fromEntries(["exact", "probable", "ambiguous", "missing"]
          .map(status => [status, results.filter(result => result.status === status).length]))
      };
    } finally {
      scroller.scrollTop = originalScrollTop;
      scroller.dispatchEvent(new Event("scroll", { bubbles: true }));
      await waitForGrid();
    }
  }

  function clearMatchMarks() {
    document.querySelector("#simplifi-match-styles")?.remove();
  }

  function markMatches(results) {
    clearMatchMarks();
    const colors = { exact: "#00ff66", probable: "#ffe600", ambiguous: "#ff1744" };
    const rules = [];
    for (const result of results) {
      for (const match of result.rows) {
        if (!match.rowKey || !colors[result.status]) continue;
        const escapedId = CSS.escape(match.rowKey);
        rules.push(`[role='row'][data-id='${escapedId}'] {` +
          `outline: 6px solid ${colors[result.status]} !important;` +
          "outline-offset: -6px !important;" +
          `box-shadow: inset 0 0 0 3px ${colors[result.status]} !important;` +
          "}");
      }
    }
    if (!rules.length) return;
    const styles = document.createElement("style");
    styles.id = "simplifi-match-styles";
    styles.textContent = rules.join("\n");
    document.documentElement.append(styles);
  }

  function scan() {
    const rows = uniqueVisible(rowSelector, 100);
    const controls = uniqueVisible(controlSelector, 150);
    const headers = uniqueVisible("[role='columnheader']", 50);
    return {
      probeVersion: "0.5.0",
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
    } else if (message && message.type === "simplifi-inspect-editor") {
      try {
        sendResponse({ ok: true, report: inspectOpenEditor() });
      } catch (error) {
        sendResponse({ ok: false, error: error.message });
      }
    } else if (message && message.type === "simplifi-match-visible") {
      const transactions = visibleTransactions();
      const safeRows = transactions.map(transactionSnapshot);
      const results = simplifiMatcher.classifyMatches(message.targets, safeRows);
      markMatches(results);
      sendResponse({
        ok: true,
        visibleRows: transactions.length,
        counts: Object.fromEntries(["exact", "probable", "ambiguous", "missing"]
          .map(status => [status, results.filter(result => result.status === status).length]))
      });
    } else if (message && message.type === "simplifi-sweep-filtered") {
      sweepFiltered(message.targets).then(result => {
        sendResponse({ ok: true, ...result });
      }).catch(error => {
        sendResponse({ ok: false, error: error.message });
      });
      return true;
    } else if (message && message.type === "simplifi-clear-matches") {
      clearMatchMarks();
      sendResponse({ ok: true });
    }
  });
})();
