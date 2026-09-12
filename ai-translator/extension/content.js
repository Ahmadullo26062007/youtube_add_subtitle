(() => {
  "use strict";

  const API_URL = "http://127.0.0.1:5055";

  const BATCH_SIZE = 25;
  const PREFETCH_AHEAD = 40; // hozirgi nuqtadan oldinga necha subtitle birinchi navbatda

  const REQ = "yt-uz-request-tracks";
  const RES = "yt-uz-response-tracks";

  const DEFAULTS = {
    enabled: true,
    showOriginal: false,
    fontSize: 22,
    bottom: 11
  };

  let settings = { ...DEFAULTS };

  let currentVideoId = null;

  let subtitles = [];
  const translations = new Map();

  let pending = new Set(); // hali tarjima qilinmagan indekslar
  let translating = false;
  let failed = false;

  let overlay = null;
  let overlayUz = null;
  let overlayOriginal = null;
  let status = null;

  let lastTime = -1;
  let lastRenderedIndex = -2;

  // --------------------------------------------------
  // Utils
  // --------------------------------------------------

  function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  function getVideoId() {
    try {
      const url = new URL(location.href);

      if (url.pathname === "/watch") {
        return url.searchParams.get("v");
      }
    } catch (e) {
      // ignore
    }

    return null;
  }

  function getVideo() {
    return document.querySelector(
      "#movie_player video, video"
    );
  }

  // --------------------------------------------------
  // UI
  // --------------------------------------------------

  function createUI() {
    if (overlay && document.body.contains(overlay)) return;

    overlay = document.createElement("div");
    overlay.id = "yt-uz-overlay";

    overlayUz = document.createElement("div");
    overlayUz.className = "yt-uz-line yt-uz-line-uz";

    overlayOriginal = document.createElement("div");
    overlayOriginal.className = "yt-uz-line yt-uz-line-original";

    overlay.appendChild(overlayUz);
    overlay.appendChild(overlayOriginal);

    status = document.createElement("div");
    status.id = "yt-uz-status";

    document.body.appendChild(overlay);
    document.body.appendChild(status);

    applySettings();
  }

  function applySettings() {
    if (!overlay) return;

    overlay.style.fontSize = `${settings.fontSize}px`;
    overlay.style.bottom = `${settings.bottom}%`;

    overlayOriginal.style.display =
      settings.showOriginal ? "block" : "none";
  }

  function showStatus(text) {
    createUI();

    status.textContent = text;
    status.classList.add("visible");

    clearTimeout(showStatus.timeout);

    showStatus.timeout = setTimeout(() => {
      status.classList.remove("visible");
    }, 2500);
  }

  function showSubtitle(uz, original) {
    createUI();

    overlayUz.textContent = uz || "";

    overlayOriginal.textContent =
      (settings.showOriginal && original && original !== uz)
        ? original
        : "";

    if (uz) {
      overlay.classList.add("visible");
    } else {
      overlay.classList.remove("visible");
    }
  }

  function clearSubtitle() {
    lastRenderedIndex = -2;
    showSubtitle("", "");
  }

  // --------------------------------------------------
  // Caption tracks (MAIN world bridge)
  // --------------------------------------------------

  function requestTracks(timeout = 3000) {
    return new Promise((resolve) => {
      const nonce = Math.random().toString(36).slice(2);

      const timer = setTimeout(() => {
        window.removeEventListener("message", onMessage);
        resolve(null);
      }, timeout);

      function onMessage(event) {
        if (event.source !== window) return;

        const data = event.data;

        if (!data || data.source !== RES) return;
        if (data.nonce !== nonce) return;

        clearTimeout(timer);
        window.removeEventListener("message", onMessage);

        resolve(data.tracks || null);
      }

      window.addEventListener("message", onMessage);

      window.postMessage(
        { source: REQ, nonce },
        location.origin
      );
    });
  }

  async function waitForTracks(attempts = 12) {
    for (let i = 0; i < attempts; i++) {
      const tracks = await requestTracks();

      if (tracks && tracks.length) return tracks;

      await sleep(1000);
    }

    return null;
  }

  function pickTrack(tracks) {
    // Avtomatik (ASR) bo'lmagan trackka ustunlik beramiz
    const preferred = ["en", "ru", "tr", "kk", "uz"];

    for (const lang of preferred) {
      const manual = tracks.find(
        t => t.languageCode === lang && t.kind !== "asr"
      );

      if (manual) return manual;
    }

    for (const lang of preferred) {
      const any = tracks.find(t => t.languageCode === lang);

      if (any) return any;
    }

    return tracks[0];
  }

  // --------------------------------------------------
  // Download captions
  // --------------------------------------------------

  async function loadSubtitles() {
    const tracks = await waitForTracks();

    if (!tracks) {
      throw new Error("Bu videoda subtitle topilmadi.");
    }

    const track = pickTrack(tracks);

    if (!track?.baseUrl) {
      throw new Error("Subtitle URL topilmadi.");
    }

    const url = new URL(track.baseUrl);
    url.searchParams.set("fmt", "json3");

    const response = await fetch(url.toString(), {
      credentials: "include"
    });

    if (!response.ok) {
      throw new Error(`Subtitle HTTP ${response.status}`);
    }

    const data = await response.json();

    const events = data.events || [];

    const result = [];

    for (const event of events) {
      if (!event.segs) continue;

      const text = event.segs
        .map(seg => seg.utf8 || "")
        .join("")
        .replace(/\s+/g, " ")
        .trim();

      if (!text) continue;

      const start = Number(event.tStartMs || 0) / 1000;

      const duration = Number(event.dDurationMs || 0) / 1000;

      result.push({
        start,
        end: start + Math.max(duration, 1),
        text
      });
    }

    // Ba'zi ASR treklarida oraliqlar ustma-ust tushadi
    for (let i = 0; i < result.length - 1; i++) {
      if (result[i].end > result[i + 1].start) {
        result[i].end = result[i + 1].start;
      }
    }

    return result.filter(x => x.end > x.start);
  }

  // --------------------------------------------------
  // Translation
  // --------------------------------------------------

  async function translateBatch(items) {
    const response = await fetch(`${API_URL}/translate`, {
      method: "POST",

      headers: {
        "Content-Type": "application/json"
      },

      body: JSON.stringify({
        target: "uz",
        items
      })
    });

    if (!response.ok) {
      let detail = "";

      try {
        const body = await response.json();
        detail = body.error ? ` — ${body.error}` : "";
      } catch (e) {
        // ignore
      }

      throw new Error(
        `Server: HTTP ${response.status}${detail}`
      );
    }

    return await response.json();
  }

  function currentIndex() {
    const video = getVideo();

    if (!video || !subtitles.length) return 0;

    const time = video.currentTime;

    let left = 0;
    let right = subtitles.length - 1;
    let best = 0;

    while (left <= right) {
      const mid = Math.floor((left + right) / 2);

      if (subtitles[mid].start <= time) {
        best = mid;
        left = mid + 1;
      } else {
        right = mid - 1;
      }
    }

    return best;
  }

  function nextBatchIndexes() {
    if (!pending.size) return [];

    const from = currentIndex();

    const near = [];
    const rest = [];

    for (const index of pending) {
      if (index >= from && index < from + PREFETCH_AHEAD) {
        near.push(index);
      } else {
        rest.push(index);
      }
    }

    near.sort((a, b) => a - b);
    rest.sort((a, b) => a - b);

    return near.concat(rest).slice(0, BATCH_SIZE);
  }

  async function translateLoop(videoId) {
    if (translating) return;

    translating = true;

    try {
      while (pending.size && videoId === currentVideoId) {
        const indexes = nextBatchIndexes();

        if (!indexes.length) break;

        const items = indexes.map(index => ({
          id: index,
          text: subtitles[index].text
        }));

        const result = await translateBatch(items);

        if (videoId !== currentVideoId) return;

        for (const item of (result.items || [])) {
          translations.set(item.id, item.translation);
          pending.delete(item.id);
        }

        // Server qaytarmagan idlar cheksiz aylanmasin
        for (const index of indexes) {
          pending.delete(index);
        }

        lastRenderedIndex = -2;

        showStatus(
          `Tarjima: ${translations.size}/${subtitles.length}`
        );

        await sleep(50);
      }

      failed = false;
    } catch (error) {
      console.error("[yt-uz]", error);

      failed = true;

      showStatus(error.message || "Tarjima serverida xatolik");
    } finally {
      translating = false;
    }
  }

  function queueTranslations() {
    pending = new Set();

    for (let i = 0; i < subtitles.length; i++) {
      if (!translations.has(i)) pending.add(i);
    }

    translateLoop(currentVideoId);
  }

  // --------------------------------------------------
  // Current subtitle
  // --------------------------------------------------

  function findSubtitle(time) {
    if (!subtitles.length) return -1;

    let left = 0;
    let right = subtitles.length - 1;

    while (left <= right) {
      const mid = Math.floor((left + right) / 2);

      const item = subtitles[mid];

      if (time < item.start) {
        right = mid - 1;
      } else if (time > item.end) {
        left = mid + 1;
      } else {
        return mid;
      }
    }

    return -1;
  }

  function updateSubtitle() {
    if (!settings.enabled) return;

    const video = getVideo();

    if (!video) return;

    const index = findSubtitle(video.currentTime);

    if (index === -1) {
      if (lastRenderedIndex !== -1) {
        lastRenderedIndex = -1;
        showSubtitle("", "");
      }

      return;
    }

    const item = subtitles[index];

    const translation = translations.get(index);

    const changed =
      index !== lastRenderedIndex ||
      (translation && overlayUz.textContent !== translation);

    if (!changed) return;

    lastRenderedIndex = index;

    // Tarjima hali tayyor bo'lmasa — originalni ko'rsatib turamiz
    showSubtitle(translation || item.text, item.text);

    // Ko'rilayotgan joy tarjimasiz bo'lsa, navbatni qayta ishga tushiramiz
    if (!translation && pending.size && !translating && !failed) {
      translateLoop(currentVideoId);
    }
  }

  // --------------------------------------------------
  // Load video
  // --------------------------------------------------

  async function loadVideo(force = false) {
    const id = getVideoId();

    if (!id) {
      currentVideoId = null;
      subtitles = [];
      clearSubtitle();
      return;
    }

    if (id === currentVideoId && !force) return;

    currentVideoId = id;

    subtitles = [];
    translations.clear();
    pending = new Set();
    failed = false;

    clearSubtitle();

    if (!settings.enabled) return;

    showStatus("Subtitle qidirilmoqda...");

    try {
      const loaded = await loadSubtitles();

      if (id !== currentVideoId) return;

      if (!loaded.length) {
        throw new Error("Subtitle bo'sh.");
      }

      subtitles = loaded;

      showStatus(`${subtitles.length} ta subtitle topildi`);

      queueTranslations();
    } catch (error) {
      console.error("[yt-uz]", error);

      showStatus(error.message || "Subtitle topilmadi");
    }
  }

  // --------------------------------------------------
  // Video time
  // --------------------------------------------------

  function tick() {
    const video = getVideo();

    if (video && subtitles.length) {
      const current = video.currentTime;

      if (Math.abs(current - lastTime) > 0.05) {
        lastTime = current;
        updateSubtitle();
      }
    }

    requestAnimationFrame(tick);
  }

  // --------------------------------------------------
  // SPA navigation
  // --------------------------------------------------

  function watchNavigation() {
    let lastUrl = location.href;

    window.addEventListener("yt-navigate-finish", () => {
      setTimeout(() => loadVideo(), 500);
    });

    setInterval(() => {
      if (location.href !== lastUrl) {
        lastUrl = location.href;
        setTimeout(() => loadVideo(), 800);
      }
    }, 700);
  }

  // --------------------------------------------------
  // Settings
  // --------------------------------------------------

  async function loadSettings() {
    try {
      const stored = await chrome.storage.local.get(DEFAULTS);
      settings = { ...DEFAULTS, ...stored };
    } catch (e) {
      settings = { ...DEFAULTS };
    }
  }

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") return;

    const wasEnabled = settings.enabled;

    for (const [key, change] of Object.entries(changes)) {
      if (key in DEFAULTS) settings[key] = change.newValue;
    }

    applySettings();

    if (!settings.enabled) {
      clearSubtitle();
    } else if (!wasEnabled) {
      lastRenderedIndex = -2;

      if (!subtitles.length) loadVideo(true);
    }
  });

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "reload") {
      loadVideo(true);
    }

    if (message?.type === "state") {
      sendResponse({
        videoId: currentVideoId,
        subtitles: subtitles.length,
        translated: translations.size,
        pending: pending.size
      });
    }

    return true;
  });

  // --------------------------------------------------
  // Start
  // --------------------------------------------------

  (async () => {
    await loadSettings();

    createUI();

    watchNavigation();

    tick();

    loadVideo(true);
  })();
})();
