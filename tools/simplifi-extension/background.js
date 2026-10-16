chrome.action.onClicked.addListener(tab => {
  openProbeWindow(tab).catch(error => console.error("Could not open Simplifi probe", error));
});

async function openProbeWindow(tab) {
  if (!Number.isInteger(tab.id)) return;
  const saved = await chrome.storage.session.get("probeWindows");
  const windows = saved.probeWindows || {};
  const previous = windows[tab.id];
  if (previous) {
    try {
      await chrome.windows.update(previous, { focused: true });
      return;
    } catch {
      delete windows[tab.id];
    }
  }
  const panel = await chrome.windows.create({
    url: chrome.runtime.getURL(`popup.html?tabId=${tab.id}`),
    type: "popup",
    width: 600,
    height: 720
  });
  windows[tab.id] = panel.id;
  await chrome.storage.session.set({ probeWindows: windows });
}
