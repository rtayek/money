let reportText = "";

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

async function copyReport() {
  await navigator.clipboard.writeText(reportText);
  document.querySelector("#status").textContent = "Sanitized report copied.";
}

document.querySelector("#scan").addEventListener("click", scan);
document.querySelector("#copy").addEventListener("click", copyReport);
