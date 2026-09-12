import os
import time
import hashlib
import sqlite3
from pathlib import Path

import requests

from flask import Flask, request, jsonify
from flask_cors import CORS
from dotenv import load_dotenv


# --------------------------------------------------
# Config
# --------------------------------------------------

BASE_DIR = Path(__file__).resolve().parent

load_dotenv(BASE_DIR / ".env")

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()

GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash").strip()

PORT = int(os.getenv("PORT", "5055"))

DATABASE = BASE_DIR / "cache.db"

MAX_ITEMS = 50

app = Flask(__name__)

CORS(
    app,
    resources={
        r"/*": {
            "origins": [
                "https://www.youtube.com",
                "https://m.youtube.com",
                # popup.html shu origin bilan /health ga murojaat qiladi
                r"chrome-extension://.*",
            ]
        }
    },
)


# --------------------------------------------------
# Database
# --------------------------------------------------

def get_db():
    conn = sqlite3.connect(DATABASE, timeout=10)

    conn.row_factory = sqlite3.Row

    conn.execute("PRAGMA journal_mode = WAL")

    return conn


def init_db():
    conn = get_db()

    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS translations (
            source_hash TEXT NOT NULL,
            target_lang TEXT NOT NULL,
            source_text TEXT NOT NULL,
            translation TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY (source_hash, target_lang)
        )
        """
    )

    conn.commit()

    conn.close()


def text_hash(text):
    return hashlib.sha256(
        text.strip().lower().encode("utf-8")
    ).hexdigest()


def get_cached_many(texts, target):
    """{original_text: translation} — faqat cache'da borlari."""

    if not texts:
        return {}

    by_hash = {text_hash(text): text for text in texts}

    hashes = list(by_hash.keys())

    result = {}

    conn = get_db()

    try:
        # SQLite'ning parametr limitiga tushib qolmaslik uchun bo'lib so'raymiz
        for start in range(0, len(hashes), 400):
            chunk = hashes[start:start + 400]

            placeholders = ",".join("?" * len(chunk))

            rows = conn.execute(
                f"""
                SELECT source_hash, translation
                FROM translations
                WHERE target_lang = ?
                AND source_hash IN ({placeholders})
                """,
                [target, *chunk],
            ).fetchall()

            for row in rows:
                original = by_hash.get(row["source_hash"])

                if original is not None:
                    result[original] = row["translation"]
    finally:
        conn.close()

    return result


def save_cached_many(pairs, target):
    """pairs: [(source_text, translation), ...]"""

    if not pairs:
        return

    now = int(time.time())

    conn = get_db()

    try:
        conn.executemany(
            """
            INSERT OR REPLACE INTO translations
            (source_hash, target_lang, source_text, translation, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            [
                (text_hash(text), target, text, translation, now)
                for text, translation in pairs
            ],
        )

        conn.commit()
    finally:
        conn.close()


# --------------------------------------------------
# Gemini
# --------------------------------------------------

PROMPT_TEMPLATE = """You are a professional subtitle translator.

Translate the following subtitle lines into natural, modern Uzbek (Latin script).

Rules:
1. Translate ONLY the text.
2. Preserve the exact ID of every line.
3. Output exactly one line per input line, in the same order.
4. Output format, tab-separated: ID<TAB>TRANSLATION
5. No explanations, no numbering, no markdown, no quotation marks.
6. Preserve personal names, brand names and numbers.
7. Preserve the tone and emotional meaning.
8. Keep it concise - it will be shown as on-screen subtitles.
9. Lines are consecutive fragments of one conversation; use the surrounding
   lines as context, but never merge or split lines.

Input:

{source}
"""


def call_gemini(items):
    """items: [{"id": 0, "text": "Hello"}, ...] -> [{"id", "translation"}, ...]"""

    source = "\n".join(
        f'{item["id"]}\t{item["text"]}' for item in items
    )

    url = (
        "https://generativelanguage.googleapis.com/"
        f"v1beta/models/{GEMINI_MODEL}:generateContent"
    )

    headers = {
        "Content-Type": "application/json",
        "x-goog-api-key": GEMINI_API_KEY,
    }

    payload = {
        "contents": [
            {
                "role": "user",
                "parts": [
                    {"text": PROMPT_TEMPLATE.format(source=source)}
                ],
            }
        ],
        "generationConfig": {
            "temperature": 0.2,
            "maxOutputTokens": 8192,
        },
    }

    last_error = None

    for attempt in range(3):
        try:
            response = requests.post(
                url, headers=headers, json=payload, timeout=90
            )
        except requests.RequestException as exc:
            last_error = f"Tarmoq xatosi: {exc}"
            time.sleep(2 * (attempt + 1))
            continue

        if response.ok:
            return parse_translation(
                extract_text(response.json()), items
            )

        last_error = (
            f"Gemini {response.status_code}: {response.text[:300]}"
        )

        # 429 / 5xx — qayta urinamiz, qolganlari uchun ma'no yo'q
        if response.status_code not in (429, 500, 502, 503, 504):
            break

        time.sleep(2 * (attempt + 1))

    raise RuntimeError(last_error or "Gemini xatosi")


def extract_text(data):
    candidates = data.get("candidates") or []

    if not candidates:
        feedback = data.get("promptFeedback") or {}

        raise RuntimeError(
            "Gemini javob qaytarmadi: "
            f"{feedback.get('blockReason', 'nomalum sabab')}"
        )

    parts = (
        candidates[0].get("content", {}).get("parts") or []
    )

    text = "".join(part.get("text", "") for part in parts)

    if not text.strip():
        raise RuntimeError("Gemini bo'sh javob qaytardi.")

    return text


def parse_translation(text, original_items):
    original_by_id = {
        str(item["id"]): item for item in original_items
    }

    parsed = {}

    for line in text.splitlines():
        line = line.strip()

        if not line:
            continue

        if "\t" in line:
            id_part, translation = line.split("\t", 1)
        elif "|" in line:
            id_part, translation = line.split("|", 1)
        else:
            continue

        id_part = id_part.strip()
        translation = translation.strip().strip('"')

        if id_part in original_by_id and translation:
            parsed[id_part] = translation

    output = []

    for item in original_items:
        translation = parsed.get(str(item["id"]))

        output.append(
            {
                "id": item["id"],
                "translation": translation or item["text"],
                "ok": bool(translation),
            }
        )

    return output


# --------------------------------------------------
# API
# --------------------------------------------------

@app.get("/health")
def health():
    return jsonify(
        {
            "ok": True,
            "model": GEMINI_MODEL,
            "api_key": bool(GEMINI_API_KEY),
            "cache": DATABASE.exists(),
        }
    )


@app.post("/translate")
def translate():
    if not GEMINI_API_KEY:
        return jsonify({"error": "GEMINI_API_KEY sozlanmagan."}), 500

    data = request.get_json(silent=True)

    if not isinstance(data, dict):
        return jsonify({"error": "JSON kerak."}), 400

    target = data.get("target", "uz")

    if target != "uz":
        return jsonify(
            {"error": "Hozircha faqat uz qo'llab-quvvatlanadi."}
        ), 400

    items = data.get("items") or []

    if not isinstance(items, list):
        return jsonify({"error": "items ro'yxat bo'lishi kerak."}), 400

    if not items:
        return jsonify({"items": []})

    if len(items) > MAX_ITEMS:
        return jsonify(
            {"error": f"Bir requestda maksimum {MAX_ITEMS} subtitle."}
        ), 400

    clean = []

    for item in items:
        if not isinstance(item, dict):
            continue

        text = str(item.get("text", "")).strip()

        if text:
            clean.append({"id": item.get("id"), "text": text})

    if not clean:
        return jsonify({"items": []})

    cached = get_cached_many([item["text"] for item in clean], target)

    final = []
    need_ai = []

    for item in clean:
        hit = cached.get(item["text"])

        if hit:
            final.append({"id": item["id"], "translation": hit})
        else:
            need_ai.append(item)

    if need_ai:
        try:
            translated = call_gemini(need_ai)
        except RuntimeError as exc:
            app.logger.error("Gemini: %s", exc)
            return jsonify({"error": str(exc)}), 502

        text_by_id = {item["id"]: item["text"] for item in need_ai}

        to_cache = []

        for item in translated:
            # Tarjima qilinmagan (fallback) qatorni cache'ga yozmaymiz
            if item.get("ok"):
                to_cache.append(
                    (text_by_id[item["id"]], item["translation"])
                )

            final.append(
                {"id": item["id"], "translation": item["translation"]}
            )

        save_cached_many(to_cache, target)

    order = {item["id"]: index for index, item in enumerate(clean)}

    final.sort(key=lambda x: order.get(x["id"], 10**9))

    return jsonify({"items": final})


@app.get("/stats")
def stats():
    conn = get_db()

    try:
        row = conn.execute(
            "SELECT COUNT(*) AS total FROM translations"
        ).fetchone()
    finally:
        conn.close()

    return jsonify({"cached": row["total"]})


init_db()


if __name__ == "__main__":
    print("YouTube Uzbek Translator server")
    print("Model:", GEMINI_MODEL)
    print("API key:", "bor" if GEMINI_API_KEY else "YO'Q — .env ni tekshiring")

    app.run(host="127.0.0.1", port=PORT, debug=True)
