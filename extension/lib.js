// Content script va popup ikkalasi ham shu faylni yuklaydi.
// Parser + storage yordamchilari shu yerda.
(() => {
  "use strict";

  const MAX_SAVED = 20;

  const DEFAULT_SETTINGS = {
    enabled: true,
    fontSize: 4.2,   // player balandligiga nisbatan %
    bgOpacity: 80,   // 0 = fon yo'q
    bottom: 8        // pastdan %
  };

  // --------------------------------------------------
  // Faylni o'qish (kodlashni avtomatik aniqlash)
  // --------------------------------------------------

  function decodeBuffer(buffer) {
    // Ko'p SRT fayllar UTF-8 emas (Windows-1251 / 1254). Avval UTF-8 ni
    // qat'iy rejimda sinaymiz — xato bersa, boshqa kodlashlarga o'tamiz.
    try {
      return {
        text: new TextDecoder("utf-8", { fatal: true }).decode(buffer),
        encoding: "utf-8"
      };
    } catch (e) {
      // davom etamiz
    }

    for (const encoding of ["windows-1251", "windows-1254", "windows-1252"]) {
      try {
        return {
          text: new TextDecoder(encoding).decode(buffer),
          encoding
        };
      } catch (e) {
        // davom etamiz
      }
    }

    return {
      text: new TextDecoder("utf-8").decode(buffer),
      encoding: "utf-8"
    };
  }

  async function readFile(file) {
    const buffer = await file.arrayBuffer();
    return decodeBuffer(buffer);
  }

  // --------------------------------------------------
  // Vaqt
  // --------------------------------------------------

  // 00:01:05,230 / 00:01:05.230 / 01:05.230 / 1:05:03
  function parseTime(value) {
    const match = String(value).trim().match(
      /^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?$/
    );

    if (!match) return null;

    const hours = Number(match[1] || 0);
    const minutes = Number(match[2]);
    const seconds = Number(match[3]);
    const ms = Number((match[4] || "0").padEnd(3, "0"));

    return hours * 3600 + minutes * 60 + seconds + ms / 1000;
  }

  function formatTime(seconds) {
    const sign = seconds < 0 ? "-" : "";
    const value = Math.abs(seconds);

    const m = Math.floor(value / 60);
    const s = Math.floor(value % 60);

    return `${sign}${m}:${String(s).padStart(2, "0")}`;
  }

  // --------------------------------------------------
  // Parser — SRT va VTT ikkalasi uchun
  // --------------------------------------------------

  function detectFormat(text) {
    if (/^\s*WEBVTT/.test(text)) return "vtt";

    // SRT vergul ishlatadi (00:00:05,230), VTT nuqta (00:00:05.230)
    if (/\d:\d{2}:\d{2},\d{1,3}/.test(text)) return "srt";
    if (/\d:\d{2}[:.]\d{2}\.\d{1,3}/.test(text)) return "vtt";

    return "srt";
  }

  function parse(raw) {
    const text = String(raw)
      .replace(/^\uFEFF/, "")
      .replace(/\r\n?/g, "\n");

    const format = detectFormat(text);

    const blocks = text.split(/\n{2,}/);

    const cues = [];

    for (const block of blocks) {
      const lines = block.split("\n").filter(line => line.trim() !== "");

      if (!lines.length) continue;

      const timeIndex = lines.findIndex(line => line.includes("-->"));

      // WEBVTT sarlavhasi, NOTE / STYLE / REGION bloklari, faqat raqamli
      // qatorlar — bularda "-->" yo'q, tashlab ketamiz
      if (timeIndex === -1) continue;

      const parts = lines[timeIndex].split("-->");

      if (parts.length < 2) continue;

      const start = parseTime(parts[0]);

      // VTT'da vaqtdan keyin sozlamalar bo'lishi mumkin:
      // "00:00:07.330 align:middle position:50%"
      const end = parseTime(parts[1].trim().split(/\s+/)[0]);

      if (start === null || end === null) continue;

      const body = lines.slice(timeIndex + 1).join("\n").trim();

      if (!body) continue;

      cues.push({
        s: Number(start.toFixed(3)),
        e: Number(Math.max(end, start + 0.2).toFixed(3)),
        t: body
      });
    }

    cues.sort((a, b) => a.s - b.s || a.e - b.e);

    return { format, cues };
  }

  // --------------------------------------------------
  // Vaqt bo'yicha qidirish
  // --------------------------------------------------

  function activeCues(cues, time) {
    if (!cues.length) return [];

    // start <= time bo'lgan oxirgi cue
    let left = 0;
    let right = cues.length - 1;
    let found = -1;

    while (left <= right) {
      const mid = (left + right) >> 1;

      if (cues[mid].s <= time) {
        found = mid;
        left = mid + 1;
      } else {
        right = mid - 1;
      }
    }

    if (found === -1) return [];

    // VTT'da cue'lar ustma-ust tushishi mumkin — bir nechtasini tekshiramiz
    const result = [];

    for (let i = found; i >= 0 && i > found - 8; i--) {
      if (cues[i].e > time) result.unshift({ i, cue: cues[i] });
    }

    return result.slice(-3);
  }

  // --------------------------------------------------
  // Matnni xavfsiz chiqarish (<i>, <b>, <u> saqlanadi)
  // --------------------------------------------------

  const ALLOWED_TAGS = {
    i: "i",
    b: "b",
    u: "u",
    em: "i",
    strong: "b"
  };

  const ENTITIES = {
    amp: "&",
    lt: "<",
    gt: ">",
    quot: '"',
    apos: "'",
    nbsp: "\u00a0"
  };

  function decodeEntities(value) {
    return value.replace(
      /&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);/g,
      (full, body) => {
        if (body[0] === "#") {
          const code = body[1] === "x" || body[1] === "X"
            ? parseInt(body.slice(2), 16)
            : parseInt(body.slice(1), 10);

          try {
            return String.fromCodePoint(code);
          } catch (e) {
            return full;
          }
        }

        const mapped = ENTITIES[body.toLowerCase()];

        return mapped === undefined ? full : mapped;
      }
    );
  }

  function renderLine(parent, line) {
    // Teglar, VTT vaqt belgilari (<00:00:01.000>) va ASS teglari ({\i1})
    const pattern =
      /<\s*(\/?)\s*([a-zA-Z][a-zA-Z0-9]*)[^>]*>|<[^>]*>|\{\\[^}]*\}/g;

    const stack = [parent];

    let last = 0;
    let match;

    while ((match = pattern.exec(line)) !== null) {
      const chunk = line.slice(last, match.index);

      if (chunk) {
        stack[stack.length - 1].appendChild(
          document.createTextNode(decodeEntities(chunk))
        );
      }

      last = pattern.lastIndex;

      const tag = (match[2] || "").toLowerCase();
      const mapped = ALLOWED_TAGS[tag];

      // <v Speaker>, <c.yellow>, vaqt belgilari — olib tashlanadi
      if (!mapped) continue;

      if (match[1] === "/") {
        if (stack.length > 1) stack.pop();
      } else {
        const element = document.createElement(mapped);
        stack[stack.length - 1].appendChild(element);
        stack.push(element);
      }
    }

    const tail = line.slice(last);

    if (tail) {
      stack[stack.length - 1].appendChild(
        document.createTextNode(decodeEntities(tail))
      );
    }
  }

  function renderInto(container, text) {
    container.textContent = "";

    const lines = String(text).split("\n");

    lines.forEach((line, index) => {
      if (index) container.appendChild(document.createElement("br"));
      renderLine(container, line);
    });
  }

  // --------------------------------------------------
  // Storage
  // --------------------------------------------------

  const subKey = (videoId) => `sub:${videoId}`;

  async function getSettings() {
    const stored = await chrome.storage.local.get("settings");

    return { ...DEFAULT_SETTINGS, ...(stored.settings || {}) };
  }

  async function saveSettings(patch) {
    const current = await getSettings();

    const next = { ...current, ...patch };

    await chrome.storage.local.set({ settings: next });

    return next;
  }

  async function getSub(videoId) {
    if (!videoId) return null;

    const stored = await chrome.storage.local.get(subKey(videoId));

    return stored[subKey(videoId)] || null;
  }

  async function listSubs() {
    const stored = await chrome.storage.local.get("subIndex");

    return stored.subIndex || [];
  }

  async function getLastSub() {
    const stored = await chrome.storage.local.get("lastSub");

    return stored.lastSub || null;
  }

  async function saveSub(videoId, record) {
    if (!videoId) throw new Error("Video aniqlanmadi.");

    const full = {
      name: record.name,
      format: record.format,
      cues: record.cues,
      offset: record.offset || 0,
      savedAt: Date.now()
    };

    const index = (await listSubs()).filter(x => x.id !== videoId);

    index.unshift({
      id: videoId,
      name: full.name,
      count: full.cues.length,
      savedAt: full.savedAt
    });

    const keep = index.slice(0, MAX_SAVED);
    const drop = index.slice(MAX_SAVED);

    await chrome.storage.local.set({
      [subKey(videoId)]: full,
      subIndex: keep,
      lastSub: {
        name: full.name,
        format: full.format,
        cues: full.cues
      }
    });

    if (drop.length) {
      await chrome.storage.local.remove(drop.map(x => subKey(x.id)));
    }

    return full;
  }

  async function setOffset(videoId, offset) {
    const record = await getSub(videoId);

    if (!record) return null;

    record.offset = Number(offset.toFixed(2));

    await chrome.storage.local.set({ [subKey(videoId)]: record });

    return record;
  }

  async function deleteSub(videoId) {
    const index = (await listSubs()).filter(x => x.id !== videoId);

    await chrome.storage.local.remove(subKey(videoId));
    await chrome.storage.local.set({ subIndex: index });
  }

  async function clearAll() {
    const index = await listSubs();

    await chrome.storage.local.remove([
      ...index.map(x => subKey(x.id)),
      "subIndex",
      "lastSub"
    ]);
  }

  self.SubLib = {
    DEFAULT_SETTINGS,
    MAX_SAVED,
    readFile,
    parse,
    parseTime,
    formatTime,
    activeCues,
    renderInto,
    getSettings,
    saveSettings,
    getSub,
    saveSub,
    setOffset,
    deleteSub,
    listSubs,
    getLastSub,
    clearAll
  };
})();
