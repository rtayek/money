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

  function findOpenEditorRoot() {
    const textareas = uniqueVisible("textarea", 20);
    const roots = textareas.map(textarea => textarea.closest("form")).filter(Boolean);
    return roots.find(form => form.querySelector("button[type='submit']")) ||
      textareas[0]?.closest("[role='dialog'], aside, [class*='Drawer']") || null;
  }

  function openEditorRoot() {
    const root = findOpenEditorRoot();
    if (!root) throw new Error("Open a transaction details panel first.");
    return root;
  }

  function inspectOpenEditor() {
    const root = openEditorRoot();
    const controls = Array.from(root.querySelectorAll("input, textarea, select, button"))
      .filter(isVisible);
    return {
      probeVersion: "0.7.0",
      root: {
        tag: root.tagName.toLowerCase(),
        role: root.getAttribute("role") || "",
        classes: simplifiProbe.shortClasses(root.className)
      },
      controlCount: controls.length,
      controls: controls.map(describeEditorControl)
    };
  }

  function labelledControl(root, label, selector) {
    return Array.from(root.querySelectorAll(selector))
      .filter(isVisible)
      .find(control => associatedLabel(control) === label) || null;
  }

  function setNativeValue(control, value) {
    const prototype = control instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype
      : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
    if (!setter) throw new Error("The Note field cannot be updated safely.");
    setter.call(control, value);
    control.dispatchEvent(new InputEvent("input", {
      bubbles: true,
      inputType: "insertText",
      data: value
    }));
    control.dispatchEvent(new Event("change", { bubbles: true }));
  }

  async function prepareOpenNote(targets) {
    const root = openEditorRoot();
    const payee = labelledControl(root, "payee", "input");
    const date = labelledControl(root, "date", "input");
    const amount = labelledControl(root, "amount", "input");
    const account = labelledControl(root, "account", "input");
    const note = labelledControl(root, "note", "textarea");
    if (!payee || !date || !amount || !account || !note) {
      throw new Error("The open transaction fields could not be identified.");
    }

    const openTransaction = {
      rowKey: "open-transaction",
      payee: payee.value,
      date: date.value,
      amount: amount.value,
      account: account.value
    };
    const matches = simplifiMatcher.classifyMatches(targets, [openTransaction])
      .filter(result => result.status === "exact");
    if (matches.length !== 1) {
      throw new Error(`Expected one exact CSV match for the open transaction; found ${matches.length}.`);
    }

    const target = matches[0].target;
    const existingNote = note.value;
    const merged = simplifiMatcher.mergeNote(existingNote, target.notes);
    if (merged.changed) {
      setNativeValue(note, merged.value);
      note.focus();
      await waitForGrid();
    }
    const updateButton = root.querySelector("button[type='submit'].MuiButton-containedPrimary");
    return {
      csvRow: target.csvRow,
      changed: merged.changed,
      preservedExistingNote: Boolean(existingNote) && merged.value.startsWith(existingNote.trimEnd()),
      updateEnabled: Boolean(updateButton && !updateButton.disabled)
    };
  }

  function waitUntil(check, message, timeout = 6000) {
    return new Promise((resolve, reject) => {
      const deadline = Date.now() + timeout;
      const poll = () => {
        const value = check();
        if (value) {
          resolve(value);
        } else if (Date.now() >= deadline) {
          reject(new Error(message));
        } else {
          setTimeout(poll, 100);
        }
      };
      poll();
    });
  }

  async function openTransaction(rowKey) {
    const selector = `[role='row'][data-id='${CSS.escape(rowKey)}']`;
    const row = await waitUntil(() => {
      const candidate = document.querySelector(selector);
      return candidate && isVisible(candidate) ? candidate : null;
    }, "A selected transaction row is no longer visible.");
    row.click();
    return waitUntil(
      () => findOpenEditorRoot(),
      "The transaction editor did not open."
    );
  }

  async function closeTransaction(root) {
    const close = root.querySelector("button[aria-label='close']");
    if (!close) throw new Error("The transaction editor Close button was not found.");
    close.click();
    await waitUntil(
      () => !document.contains(root) || !isVisible(root),
      "The transaction editor did not close."
    );
  }

  async function verifySavedNote(rowKey, target) {
    const root = await openTransaction(rowKey);
    const note = labelledControl(root, "note", "textarea");
    if (!note) throw new Error("The saved Note field could not be found for verification.");
    const verification = simplifiMatcher.mergeNote(note.value, target.notes);
    if (verification.changed) {
      throw new Error(`CSV row ${target.csvRow} did not contain the expected saved Note.`);
    }
    await closeTransaction(root);
  }

  async function applyVisibleNotes(targets, limit) {
    if (findOpenEditorRoot()) throw new Error("Close the open transaction panel before starting the batch.");
    const transactions = visibleTransactions();
    const safeRows = transactions.map(transactionSnapshot);
    const candidates = simplifiMatcher.exactMatchCandidates(targets, safeRows, limit);
    if (!candidates.length) throw new Error("No exact matched transactions are visible.");

    const report = {
      selectedCsvRows: candidates.map(candidate => candidate.target.csvRow),
      savedCsvRows: [],
      alreadyPresentCsvRows: [],
      completed: true,
      error: ""
    };
    for (const candidate of candidates) {
      const target = candidate.target;
      const rowKey = candidate.rows[0].rowKey;
      try {
        const root = await openTransaction(rowKey);
        const prepared = await prepareOpenNote([target]);
        if (!prepared.changed) {
          report.alreadyPresentCsvRows.push(target.csvRow);
          await closeTransaction(root);
          continue;
        }
        const update = root.querySelector("button[type='submit'].MuiButton-containedPrimary");
        if (!update || update.disabled) {
          throw new Error(`Update did not become available for CSV row ${target.csvRow}.`);
        }
        update.click();
        await waitUntil(
          () => !document.contains(root) || !isVisible(root),
          `Update did not finish for CSV row ${target.csvRow}.`
        );
        await verifySavedNote(rowKey, target);
        report.savedCsvRows.push(target.csvRow);
      } catch (error) {
        report.completed = false;
        report.error = error.message;
        break;
      }
    }
    return report;
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
      probeVersion: "0.7.0",
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
    } else if (message && message.type === "simplifi-prepare-open-note") {
      prepareOpenNote(message.targets).then(result => {
        sendResponse({ ok: true, ...result });
      }).catch(error => {
        sendResponse({ ok: false, error: error.message });
      });
      return true;
    } else if (message && message.type === "simplifi-apply-visible-notes") {
      applyVisibleNotes(message.targets, 5).then(result => {
        sendResponse({ ok: true, ...result });
      }).catch(error => {
        sendResponse({ ok: false, error: error.message });
      });
      return true;
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
