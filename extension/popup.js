(() => {
  "use strict";

  const L = self.SubLib;

  const $ = (id) => document.getElementById(id);

  const fileInput = $("file");
  const currentBox = $("current");
  const applyLastBtn = $("applyLast");
  const removeBtn = $("remove");
  const clearAllBtn = $("clearAll");
  const savedBox = $("saved");

  const enabled = $("enabled");
  const fontSize = $("fontSize");
  const bgOpacity = $("bgOpacity");
  const bottom = $("bottom");
  const offset = $("offset");

  let videoId = null;
  let settings = { ...L.DEFAULT_SETTINGS };
  let record = null;
  let lastSub = null;

  // --------------------------------------------------
  // Aktiv video tab
  // --------------------------------------------------

  async function activeVideoId() {
    const [tab] = await chrome.tabs.query({
      active: true,
      currentWindow: true
    });

    if (!tab?.url) return null;

    return L.videoIdFromUrl(tab.url);
  }

  // --------------------------------------------------
  // Render
  // --------------------------------------------------

  function renderCurrent() {
    currentBox.textContent = "";

    if (!videoId) {
      currentBox.className = "muted";
      currentBox.textContent = "YouTube yoki Dailymotion video sahifasi ochilmagan.";

      fileInput.disabled = true;
      applyLastBtn.hidden = true;
      removeBtn.hidden = true;
      offset.disabled = true;

      return;
    }

    fileInput.disabled = false;

    if (record) {
      currentBox.className = "";

      const name = document.createElement("b");
      name.textContent = record.name;

      currentBox.appendChild(name);
      currentBox.appendChild(document.createElement("br"));

      currentBox.appendChild(
        document.createTextNode(
          `${record.format.toUpperCase()} · ${record.cues.length} ta qator`
        )
      );

      removeBtn.hidden = false;
      applyLastBtn.hidden = true;
      offset.disabled = false;
    } else {
      currentBox.className = "muted";
      currentBox.textContent = "Bu videoga subtitle ulanmagan.";

      removeBtn.hidden = true;
      offset.disabled = true;

      if (lastSub) {
        applyLastBtn.hidden = false;
        applyLastBtn.textContent = `↩ Oxirgi faylni qo'llash: ${lastSub.name}`;
      } else {
        applyLastBtn.hidden = true;
      }
    }
  }

  function renderSettings() {
    enabled.checked = settings.enabled;

    fontSize.value = settings.fontSize;
    bgOpacity.value = settings.bgOpacity;
    bottom.value = settings.bottom;

    $("fontSizeValue").textContent = Number(settings.fontSize).toFixed(1);
    $("bgOpacityValue").textContent = `${settings.bgOpacity}%`;
    $("bottomValue").textContent = `${settings.bottom}%`;
  }

  function renderOffset() {
    const value = record ? (record.offset || 0) : 0;

    offset.value = value;

    $("offsetValue").textContent =
      `${value > 0 ? "+" : ""}${Number(value).toFixed(1)}s`;
  }

  async function renderSaved() {
    const list = await L.listSubs();

    savedBox.textContent = list.length
      ? `${list.length} ta video uchun saqlangan (maksimum ${L.MAX_SAVED}).`
      : "Hali hech narsa saqlanmagan.";

    clearAllBtn.disabled = !list.length;
  }

  // --------------------------------------------------
  // Amallar
  // --------------------------------------------------

  fileInput.addEventListener("change", async () => {
    const file = fileInput.files[0];

    if (!file || !videoId) return;

    currentBox.className = "muted";
    currentBox.textContent = "O'qilmoqda...";

    try {
      const { text, encoding } = await L.readFile(file);

      const parsed = L.parse(text);

      if (!parsed.cues.length) {
        currentBox.textContent =
          "Fayldan subtitle o'qib bo'lmadi (format noto'g'ri?).";
        return;
      }

      record = await L.saveSub(videoId, {
        name: file.name,
        format: parsed.format,
        cues: parsed.cues,
        offset: 0
      });

      lastSub = await L.getLastSub();

      if (!settings.enabled) {
        settings = await L.saveSettings({ enabled: true });
        renderSettings();
      }

      renderCurrent();
      renderOffset();
      renderSaved();

      if (encoding !== "utf-8") {
        currentBox.appendChild(document.createElement("br"));
        currentBox.appendChild(
          document.createTextNode(`kodlash: ${encoding}`)
        );
      }
    } catch (error) {
      console.error(error);
      currentBox.textContent = "Faylni o'qishda xatolik.";
    } finally {
      fileInput.value = "";
    }
  });

  applyLastBtn.addEventListener("click", async () => {
    if (!lastSub || !videoId) return;

    record = await L.saveSub(videoId, {
      name: lastSub.name,
      format: lastSub.format,
      cues: lastSub.cues,
      offset: 0
    });

    renderCurrent();
    renderOffset();
    renderSaved();
  });

  removeBtn.addEventListener("click", async () => {
    if (!videoId) return;

    await L.deleteSub(videoId);

    record = null;

    renderCurrent();
    renderOffset();
    renderSaved();
  });

  clearAllBtn.addEventListener("click", async () => {
    await L.clearAll();

    record = null;
    lastSub = null;

    renderCurrent();
    renderOffset();
    renderSaved();
  });

  enabled.addEventListener("change", async () => {
    settings = await L.saveSettings({ enabled: enabled.checked });
  });

  function bindRange(input, key, format) {
    input.addEventListener("input", async () => {
      const value = Number(input.value);

      $(`${input.id}Value`).textContent = format(value);

      settings = await L.saveSettings({ [key]: value });
    });
  }

  bindRange(fontSize, "fontSize", v => v.toFixed(1));
  bindRange(bgOpacity, "bgOpacity", v => `${v}%`);
  bindRange(bottom, "bottom", v => `${v}%`);

  offset.addEventListener("input", async () => {
    const value = Number(offset.value);

    $("offsetValue").textContent =
      `${value > 0 ? "+" : ""}${value.toFixed(1)}s`;

    if (!videoId || !record) return;

    record.offset = value;

    await L.setOffset(videoId, value);
  });

  // --------------------------------------------------
  // Start
  // --------------------------------------------------

  (async () => {
    settings = await L.getSettings();
    videoId = await activeVideoId();
    record = await L.getSub(videoId);
    lastSub = await L.getLastSub();

    renderSettings();
    renderCurrent();
    renderOffset();
    renderSaved();
  })();
})();
