(() => {
  "use strict";

  const L = self.SubLib;

  let settings = { ...L.DEFAULT_SETTINGS };

  let videoId = null;
  let cues = [];
  let offset = 0;
  let subName = "";

  let box = null;
  let textEl = null;
  let toastEl = null;
  let dropEl = null;

  let lastCueKey = "";
  let lastLayoutKey = "";

  const IS_DM = /(^|\.)dailymotion\.com$/.test(location.hostname);
  const IS_TOP = window === window.top;

  // Dailymotion player iframe (geo.dailymotion.com) ichida ishlaydi. Sayt SPA
  // bo'lgani uchun iframe URL'i har doim ham yangilanmaydi — shuning uchun
  // asosiy sahifa joriy video ID'sini postMessage orqali yuboradi.
  let parentVideoId = null;
  let lastMouseMove = 0;

  // --------------------------------------------------
  // DOM yordamchilari
  // --------------------------------------------------

  function getVideo() {
    if (IS_DM) {
      return (
        document.querySelector("video#video") ||
        document.querySelector("video")
      );
    }

    return (
      document.querySelector("#movie_player video.html5-main-video") ||
      document.querySelector("#movie_player video")
    );
  }

  function getPlayer() {
    if (!IS_DM) return document.querySelector("#movie_player");

    const video = getVideo();

    if (!video) return null;

    const player = video.closest(".player") || video.parentElement;

    // Fullscreen player ichidagi chuqurroq elementga qo'yilgan bo'lsa,
    // overlay o'sha element ichida bo'lishi kerak, aks holda ko'rinmaydi.
    const fs = document.fullscreenElement;

    if (fs && fs !== video && player.contains(fs) && fs.contains(video)) {
      return fs;
    }

    return player;
  }

  function isAdShowing(player) {
    return !IS_DM && player.classList.contains("ad-showing");
  }

  function areControlsVisible(player) {
    if (!IS_DM) return !player.classList.contains("ytp-autohide");

    // Dailymotion controls holatini class orqali bildirmaydi:
    // sichqoncha qimirlaganda yoki pauzada ko'rinadi deb hisoblaymiz.
    const video = getVideo();

    return (video && video.paused) || Date.now() - lastMouseMove < 3000;
  }

  function getVideoId() {
    if (IS_DM && parentVideoId) return parentVideoId;

    return L.videoIdFromUrl(location.href);
  }

  // --------------------------------------------------
  // Overlay
  // --------------------------------------------------

  function ensureUI() {
    const player = getPlayer();

    if (!player) return null;

    if (!box) {
      box = document.createElement("div");
      box.id = "ysl-box";

      textEl = document.createElement("div");
      textEl.id = "ysl-text";

      box.appendChild(textEl);
    }

    if (!toastEl) {
      toastEl = document.createElement("div");
      toastEl.id = "ysl-toast";
    }

    if (!dropEl) {
      dropEl = document.createElement("div");
      dropEl.id = "ysl-drop";
      dropEl.textContent = ".srt / .vtt faylni shu yerga tashlang";
    }

    // Overlay absolute joylashadi — player "static" bo'lsa, siljib ketadi
    if (IS_DM && getComputedStyle(player).position === "static") {
      player.style.position = "relative";
    }

    // Player elementi SPA navigatsiyada almashishi mumkin
    if (box.parentElement !== player) player.appendChild(box);
    if (toastEl.parentElement !== player) player.appendChild(toastEl);
    if (dropEl.parentElement !== player) player.appendChild(dropEl);

    return player;
  }

  function hideBox() {
    if (box) box.classList.remove("visible");
    lastCueKey = "";
  }

  function toast(message) {
    if (!ensureUI()) return;

    toastEl.textContent = message;
    toastEl.classList.add("visible");

    clearTimeout(toast.timer);

    toast.timer = setTimeout(() => {
      toastEl.classList.remove("visible");
    }, 2200);
  }

  // Stilni har kadrda emas, faqat o'zgarganda yozamiz
  function applyLayout(player) {
    const height = player.clientHeight || 360;

    // Controls ko'rinib turganda subtitle'ni biroz tepaga suramiz
    const controlsVisible = areControlsVisible(player);

    const key = [
      height,
      controlsVisible,
      settings.fontSize,
      settings.bgOpacity,
      settings.bottom
    ].join("|");

    if (key === lastLayoutKey) return;

    lastLayoutKey = key;

    // Shrift player balandligiga nisbatan — fullscreen'da ham mos bo'ladi
    const px = Math.min(
      72,
      Math.max(12, (height * settings.fontSize) / 100)
    );

    box.style.fontSize = `${px}px`;

    box.style.bottom =
      `${settings.bottom + (controlsVisible ? 5 : 0)}%`;

    textEl.style.background =
      settings.bgOpacity > 0
        ? `rgba(0, 0, 0, ${settings.bgOpacity / 100})`
        : "transparent";
  }

  function renderCues(active) {
    if (!active.length) {
      if (lastCueKey !== "") {
        lastCueKey = "";
        box.classList.remove("visible");
      }

      return;
    }

    const key = active.map(x => x.i).join(",");

    if (key === lastCueKey) return;

    lastCueKey = key;

    L.renderInto(
      textEl,
      active.map(x => x.cue.t).join("\n")
    );

    box.classList.add("visible");
  }

  // --------------------------------------------------
  // Asosiy sikl
  // --------------------------------------------------

  function tick() {
    requestAnimationFrame(tick);

    const player = getPlayer();

    if (!player) return;

    if (!settings.enabled || !cues.length) {
      hideBox();
      return;
    }

    // Reklama vaqtida subtitle chiqmasin
    if (isAdShowing(player)) {
      hideBox();
      return;
    }

    const video = getVideo();

    if (!video) {
      hideBox();
      return;
    }

    ensureUI();

    applyLayout(player);

    // Pause bo'lsa currentTime o'zgarmaydi -> subtitle joyida turadi.
    // Seek qilinsa currentTime sakraydi -> binary search yangi cue'ni topadi.
    renderCues(L.activeCues(cues, video.currentTime - offset));
  }

  // --------------------------------------------------
  // Subtitle yuklash / saqlash
  // --------------------------------------------------

  async function applyStored() {
    cues = [];
    offset = 0;
    subName = "";

    hideBox();

    if (!videoId) return;

    const record = await L.getSub(videoId);

    if (!record) return;

    cues = record.cues || [];
    offset = record.offset || 0;
    subName = record.name || "";

    if (cues.length) {
      toast(`${subName} — ${cues.length} ta qator`);
    }
  }

  async function loadFile(file) {
    if (!videoId) {
      toast("Avval video sahifasini oching.");
      return;
    }

    const name = file.name.toLowerCase();

    if (!/\.(srt|vtt|txt)$/.test(name)) {
      toast("Faqat .srt yoki .vtt fayl.");
      return;
    }

    try {
      const { text, encoding } = await L.readFile(file);

      const parsed = L.parse(text);

      if (!parsed.cues.length) {
        toast("Fayldan subtitle o'qib bo'lmadi.");
        return;
      }

      await L.saveSub(videoId, {
        name: file.name,
        format: parsed.format,
        cues: parsed.cues,
        offset: 0
      });

      toast(
        `${parsed.format.toUpperCase()} · ${parsed.cues.length} ta qator` +
        (encoding === "utf-8" ? "" : ` · ${encoding}`)
      );
    } catch (error) {
      console.error("[ysl]", error);
      toast("Faylni o'qishda xatolik.");
    }
  }

  // --------------------------------------------------
  // Drag & drop
  // --------------------------------------------------

  function hasFiles(event) {
    const types = event.dataTransfer?.types;

    return types && Array.prototype.includes.call(types, "Files");
  }

  function showDrop(visible) {
    if (!ensureUI()) return;

    dropEl.classList.toggle("visible", visible);
  }

  function watchDragDrop() {
    let depth = 0;

    window.addEventListener("dragenter", (event) => {
      if (!hasFiles(event) || !getVideoId()) return;

      depth++;
      showDrop(true);
    });

    window.addEventListener("dragover", (event) => {
      if (!hasFiles(event) || !getVideoId()) return;

      event.preventDefault();
    });

    window.addEventListener("dragleave", () => {
      depth = Math.max(0, depth - 1);

      if (!depth) showDrop(false);
    });

    window.addEventListener("drop", (event) => {
      if (!hasFiles(event) || !getVideoId()) return;

      event.preventDefault();

      depth = 0;
      showDrop(false);

      const file = event.dataTransfer.files[0];

      if (file) loadFile(file);
    });
  }

  // --------------------------------------------------
  // Klaviatura: [ va ] bilan vaqtni siljitish
  // --------------------------------------------------

  function isTyping(target) {
    if (!target) return false;

    const tag = target.tagName;

    return (
      tag === "INPUT" ||
      tag === "TEXTAREA" ||
      target.isContentEditable === true
    );
  }

  function watchKeys() {
    document.addEventListener("keydown", async (event) => {
      if (event.ctrlKey || event.altKey || event.metaKey) return;
      if (isTyping(event.target)) return;
      if (!cues.length || !videoId) return;

      let step = 0;

      if (event.key === "[") step = -0.5;
      else if (event.key === "]") step = 0.5;
      else return;

      event.preventDefault();
      event.stopPropagation();

      offset = Number((offset + step).toFixed(2));

      lastCueKey = "";

      toast(
        `Siljish: ${offset > 0 ? "+" : ""}${offset.toFixed(1)}s`
      );

      await L.setOffset(videoId, offset);
    }, true);
  }

  // --------------------------------------------------
  // Navigatsiya
  // --------------------------------------------------

  async function onNavigate() {
    const id = getVideoId();

    if (id === videoId) return;

    videoId = id;
    lastCueKey = "";
    lastLayoutKey = "";

    sendVideoIdToFrames();

    await applyStored();
  }

  // --------------------------------------------------
  // Dailymotion: sahifa <-> player iframe
  // --------------------------------------------------

  function isDmOrigin(origin) {
    try {
      return /(^|\.)dailymotion\.com$/.test(new URL(origin).hostname);
    } catch (e) {
      return false;
    }
  }

  function sendVideoIdToFrames() {
    if (!IS_DM || !IS_TOP) return;

    for (const frame of document.querySelectorAll("iframe")) {
      if (!isDmOrigin(frame.src)) continue;

      frame.contentWindow?.postMessage({ ysl: "videoId", id: videoId }, "*");
    }
  }

  function watchFrames() {
    if (!IS_DM) return;

    window.addEventListener("mousemove", () => {
      lastMouseMove = Date.now();
    }, true);

    if (IS_TOP) {
      // Iframe tayyor bo'lganda o'zi so'raydi
      window.addEventListener("message", (event) => {
        if (event.data?.ysl !== "ask" || !isDmOrigin(event.origin)) return;

        event.source?.postMessage({ ysl: "videoId", id: videoId }, "*");
      });

      return;
    }

    window.addEventListener("message", (event) => {
      if (event.source !== window.parent) return;
      if (event.data?.ysl !== "videoId" || !isDmOrigin(event.origin)) return;

      const id = event.data.id;

      if (id !== null && !(typeof id === "string" && /^dm:/.test(id) &&
        L.DM_ID.test(id.slice(3)))) return;

      parentVideoId = id;

      onNavigate();
    });

    window.parent.postMessage({ ysl: "ask" }, "*");
  }

  function watchNavigation() {
    let lastUrl = location.href;

    window.addEventListener("yt-navigate-finish", () => {
      setTimeout(onNavigate, 300);
    });

    setInterval(() => {
      if (location.href !== lastUrl) {
        lastUrl = location.href;
        setTimeout(onNavigate, 300);
      }
    }, 500);
  }

  // --------------------------------------------------
  // Storage o'zgarishlari (popup shu orqali gaplashadi)
  // --------------------------------------------------

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") return;

    if (changes.settings) {
      settings = { ...L.DEFAULT_SETTINGS, ...(changes.settings.newValue || {}) };

      lastLayoutKey = "";

      if (!settings.enabled) hideBox();
    }

    if (videoId && changes[`sub:${videoId}`]) {
      const next = changes[`sub:${videoId}`].newValue;

      if (!next) {
        cues = [];
        offset = 0;
        subName = "";
        hideBox();
        toast("Subtitle o'chirildi.");
        return;
      }

      const isNewFile = next.name !== subName ||
        (next.cues || []).length !== cues.length;

      cues = next.cues || [];
      offset = next.offset || 0;
      subName = next.name || "";

      lastCueKey = "";

      if (isNewFile) {
        toast(`${subName} — ${cues.length} ta qator`);
      }
    }
  });

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "state") {
      sendResponse({
        videoId,
        name: subName,
        count: cues.length,
        offset
      });
    }

    return true;
  });

  // --------------------------------------------------
  // Start
  // --------------------------------------------------

  (async () => {
    settings = await L.getSettings();

    videoId = getVideoId();

    await applyStored();

    watchFrames();
    sendVideoIdToFrames();
    watchNavigation();
    watchDragDrop();
    watchKeys();

    tick();
  })();
})();
