const DEFAULTS = {
  enabled: true,
  showOriginal: false,
  fontSize: 22,
  bottom: 11
};

const API_URL = "http://127.0.0.1:5055";

const toggle = document.getElementById("toggle");
const reload = document.getElementById("reload");
const showOriginal = document.getElementById("showOriginal");
const fontSize = document.getElementById("fontSize");
const bottom = document.getElementById("bottom");
const statusBox = document.getElementById("status");

let settings = { ...DEFAULTS };

async function save(patch) {
  settings = { ...settings, ...patch };
  await chrome.storage.local.set(patch);
  renderToggle();
}

function renderToggle() {
  toggle.textContent = settings.enabled
    ? "Yoqilgan"
    : "O'chirilgan";

  toggle.classList.toggle("off", !settings.enabled);
}

async function activeTab() {
  const tabs = await chrome.tabs.query({
    active: true,
    currentWindow: true
  });

  return tabs[0] || null;
}

async function send(message) {
  const tab = await activeTab();

  if (!tab?.id) return null;

  try {
    return await chrome.tabs.sendMessage(tab.id, message);
  } catch (error) {
    return null;
  }
}

async function renderStatus() {
  const lines = [];

  const state = await send({ type: "state" });

  if (state?.videoId) {
    lines.push(
      `Subtitle: ${state.subtitles} ta`,
      `Tarjima: ${state.translated} ta` +
        (state.pending ? ` (${state.pending} navbatda)` : "")
    );
  } else {
    lines.push("YouTube video sahifasi ochilmagan.");
  }

  try {
    const response = await fetch(`${API_URL}/health`, {
      cache: "no-store"
    });

    const data = await response.json();

    lines.push(`Server: ✅ ${data.model}`);
  } catch (error) {
    lines.push("Server: ❌ ishlamayapti (localhost:5055)");
  }

  statusBox.textContent = "";

  for (const line of lines) {
    const div = document.createElement("div");
    div.textContent = line;
    statusBox.appendChild(div);
  }
}

toggle.addEventListener("click", () => {
  save({ enabled: !settings.enabled });
});

showOriginal.addEventListener("change", () => {
  save({ showOriginal: showOriginal.checked });
});

fontSize.addEventListener("input", () => {
  save({ fontSize: Number(fontSize.value) });
});

bottom.addEventListener("input", () => {
  save({ bottom: Number(bottom.value) });
});

reload.addEventListener("click", async () => {
  await send({ type: "reload" });
  setTimeout(renderStatus, 1500);
});

(async () => {
  settings = {
    ...DEFAULTS,
    ...(await chrome.storage.local.get(DEFAULTS))
  };

  showOriginal.checked = settings.showOriginal;
  fontSize.value = settings.fontSize;
  bottom.value = settings.bottom;

  renderToggle();
  renderStatus();
})();
