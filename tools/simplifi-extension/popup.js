let reportText = "";
let csvRecords = [];

async function activeTab() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  return tabs[0];
}

async function scan() {
  const status = document.querySelector("#status");
  const output = document.querySelector("#report");
  const copy = document.querySelector("#copy");
  status.textContent = "Scanning...";
  output.textContent = "";
  copy.disabled = true;

  try {
    const tab = await activeTab();
    if (!tab || !tab.url || !tab.url.startsWith("https://simplifi.quicken.com/")) {
      throw new Error("The active tab is not Quicken Simplifi.");
    }
    const response = await chrome.tabs.sendMessage(tab.id, { type: "simplifi-probe-scan" });
    if (!response || !response.ok) throw new Error("The Simplifi page did not answer.");
    reportText = JSON.stringify(response.report, null, 2);
    output.textContent = reportText;
    copy.disabled = false;
    status.textContent = `${response.report.counts.rowCandidates} row candidates and ${response.report.counts.controls} controls found.`;
  } catch (error) {
    status.textContent = error.message + " Reload the Simplifi tab after installing the extension.";
  }
}

async function inspectEditor() {
  const status = document.querySelector("#status");
  const output = document.querySelector("#report");
  const copy = document.querySelector("#copy");
  status.textContent = "Inspecting open transaction...";
  output.textContent = "";
  copy.disabled = true;
  try {
    const tab = await activeTab();
    const response = await chrome.tabs.sendMessage(tab.id, { type: "simplifi-inspect-editor" });
    if (!response || !response.ok) throw new Error(response?.error || "The Simplifi page did not answer.");
    reportText = JSON.stringify(response.report, null, 2);
    output.textContent = reportText;
    copy.disabled = false;
    status.textContent = `${response.report.controlCount} editor controls found; nothing changed.`;
  } catch (error) {
    status.textContent = error.message;
  }
}

async function copyReport() {
  await navigator.clipboard.writeText(reportText);
  document.querySelector("#status").textContent = "Sanitized report copied.";
}

async function loadCsv(event) {
  const file = event.target.files[0];
  const status = document.querySelector("#matchStatus");
  const match = document.querySelector("#match");
  const sweep = document.querySelector("#sweep");
  const prepare = document.querySelector("#prepareNote");
  if (!file) return;
  try {
    csvRecords = simplifiMatcher.recordsFromCsv(await file.text());
    const required = ["Date", "Account", "Payee", "Amount", "Notes"];
    const headers = csvRecords.length ? Object.keys(csvRecords[0]) : [];
    const missing = required.filter(header => !headers.includes(header));
    if (missing.length) throw new Error(`Missing columns: ${missing.join(", ")}`);
    match.disabled = false;
    sweep.disabled = false;
    prepare.disabled = false;
    status.textContent = `${csvRecords.length} CSV rows loaded.`;
  } catch (error) {
    csvRecords = [];
    match.disabled = true;
    sweep.disabled = true;
    prepare.disabled = true;
    status.textContent = error.message;
  }
}

function filteredTargets() {
  const filter = document.querySelector("#payeeFilter").value.trim().toLowerCase();
  return csvRecords
    .filter(record => record.Notes.trim())
    .filter(record => !filter || record.Payee.toLowerCase().includes(filter))
    .map(record => ({
      csvRow: record.csvRow,
      date: record.Date,
      account: record.Account,
      payee: record.Payee,
      amount: record.Amount,
      category: record.Category || "",
      notes: record.Notes
    }));
}

async function matchVisibleRows() {
  const status = document.querySelector("#matchStatus");
  const output = document.querySelector("#matchReport");
  const targets = filteredTargets();
  try {
    const tab = await activeTab();
    const response = await chrome.tabs.sendMessage(tab.id, {
      type: "simplifi-match-visible",
      targets
    });
    if (!response || !response.ok) throw new Error("The Simplifi page did not answer.");
    const report = {
      csvTargets: targets.length,
      visibleTransactionRows: response.visibleRows,
      ...response.counts,
      legend: "green=exact, yellow=probable account mismatch, red=ambiguous"
    };
    output.textContent = JSON.stringify(report, null, 2);
    status.textContent = `${response.counts.exact} exact matches; no Simplifi data changed.`;
  } catch (error) {
    status.textContent = error.message;
  }
}

async function sweepFilteredRows() {
  const status = document.querySelector("#matchStatus");
  const output = document.querySelector("#matchReport");
  const sweep = document.querySelector("#sweep");
  const targets = filteredTargets();
  status.textContent = "Sweeping filtered rows... Simplifi will scroll temporarily.";
  output.textContent = "";
  sweep.disabled = true;
  try {
    const tab = await activeTab();
    const response = await chrome.tabs.sendMessage(tab.id, {
      type: "simplifi-sweep-filtered",
      targets
    });
    if (!response || !response.ok) {
      throw new Error(response?.error || "The Simplifi page did not answer.");
    }
    const report = simplifiMatcher.sweepReport(targets.length, response);
    output.textContent = JSON.stringify(report, null, 2);
    status.textContent = response.truncated
      ? `Sweep stopped early after ${response.scannedRows} rows; no Simplifi data changed.`
      : `${response.counts.exact} exact matches across ${response.scannedRows} rows; no Simplifi data changed.`;
  } catch (error) {
    status.textContent = error.message;
  } finally {
    sweep.disabled = csvRecords.length === 0;
  }
}

async function prepareOpenNote() {
  const status = document.querySelector("#matchStatus");
  const output = document.querySelector("#matchReport");
  const prepare = document.querySelector("#prepareNote");
  prepare.disabled = true;
  status.textContent = "Preparing the open Note; Update will not be clicked.";
  try {
    const tab = await activeTab();
    const response = await chrome.tabs.sendMessage(tab.id, {
      type: "simplifi-prepare-open-note",
      targets: filteredTargets()
    });
    if (!response || !response.ok) {
      throw new Error(response?.error || "The Simplifi page did not answer.");
    }
    output.textContent = JSON.stringify({
      matchedCsvRow: response.csvRow,
      notePrepared: response.changed,
      existingNotePreserved: response.preservedExistingNote,
      updateButtonEnabled: response.updateEnabled,
      saved: false
    }, null, 2);
    status.textContent = response.changed
      ? `CSV row ${response.csvRow} prepared. Inspect the Note; Update was not clicked.`
      : `CSV row ${response.csvRow} was already present; nothing changed.`;
  } catch (error) {
    status.textContent = error.message;
  } finally {
    prepare.disabled = csvRecords.length === 0;
  }
}

async function clearMatches() {
  const tab = await activeTab();
  await chrome.tabs.sendMessage(tab.id, { type: "simplifi-clear-matches" });
  document.querySelector("#matchStatus").textContent = "Match marks cleared.";
}

document.querySelector("#scan").addEventListener("click", scan);
document.querySelector("#inspectEditor").addEventListener("click", inspectEditor);
document.querySelector("#copy").addEventListener("click", copyReport);
document.querySelector("#csv").addEventListener("change", loadCsv);
document.querySelector("#match").addEventListener("click", matchVisibleRows);
document.querySelector("#sweep").addEventListener("click", sweepFilteredRows);
document.querySelector("#prepareNote").addEventListener("click", prepareOpenNote);
document.querySelector("#clear").addEventListener("click", clearMatches);
