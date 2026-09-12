# YouTube Uzbek Translator

YouTube subtitlelarini Gemini orqali o'zbek tiliga tarjima qilib, video ustiga
overlay qilib chiqaradigan Chrome extension + Flask backend.

```
YouTube video
   │  original captions (json3)
   ▼
Chrome Extension  ──POST /translate──▶  Flask (SQLite cache)  ──▶  Gemini
   │                                                                 │
   ◀────────────────────── o'zbekcha subtitle ◀──────────────────────┘
```

API kaliti faqat backendda saqlanadi, extension ichida emas.

## Struktura

```
extension/
├── manifest.json     MV3
├── page.js           MAIN world — caption tracklarni YouTube playerdan oladi
├── content.js        subtitle yuklash, tarjima navbati, overlay
├── content.css       overlay stillari
├── popup.html/js     yoqish/o'chirish, shrift, holat
server/
├── app.py            Flask API (/health, /translate, /stats)
├── requirements.txt
├── run.sh
└── .env.example
```

## 1. Backend

```bash
cd server
cp .env.example .env
# .env ichiga GEMINI_API_KEY ni yozing: https://aistudio.google.com/apikey
./run.sh
```

Tekshirish:

```bash
curl http://127.0.0.1:5055/health
# {"ok":true,"model":"gemini-2.5-flash","api_key":true,"cache":true}
```

`api_key: false` bo'lsa — `.env` da kalit yo'q.

**Port 5055.** Sababi: 5000-portda boshqa loyiha (`mahwa translator extensions`)
ishlab turibdi. Portni o'zgartirsangiz, `.env` dagi `PORT` bilan birga
`extension/manifest.json` (host_permissions), `extension/content.js` va
`extension/popup.js` dagi `API_URL` ni ham yangilang.

## 2. Extension

1. `chrome://extensions/`
2. Developer mode → ON
3. Load unpacked → `extension/` papkasini tanlang
4. YouTube sahifasini refresh qiling

## 3. Ishlashi

- `page.js` MAIN world'da `#movie_player.getPlayerResponse()` dan caption
  tracklarni oladi va `postMessage` orqali content scriptga uzatadi.
  (Content script ISOLATED world'da bo'lgani uchun `ytInitialPlayerResponse`
  ga to'g'ridan-to'g'ri kira olmaydi.)
- Track `&fmt=json3` bilan yuklanadi va `{start, end, text}` ro'yxatiga aylanadi.
- Til tanlash tartibi: `en → ru → tr → kk → uz`, avtomatik (ASR) bo'lmagan
  trackka ustunlik beriladi.
- Tarjima 25 tadan batch bo'lib yuboriladi. **Navbat hozirgi ko'rilayotgan
  joydan boshlanadi** (prefetch) — shuning uchun video boshidan emas,
  o'rtasidan ko'rsangiz ham kutish qisqa bo'ladi.
- Tarjima hali tayyor bo'lmasa, original matn ko'rsatib turiladi.

## 4. Cache

Har bir matn `sha256(lower(strip(text)))` bo'yicha SQLite'ga yoziladi
(`server/cache.db`). Bir xil subtitle ikkinchi marta Gemini'ga yuborilmaydi —
takroriy iboralar ko'p bo'lgan videolarda bu sezilarli tejaydi.

Tarjima qilinmagan (fallback) qatorlar cache'ga yozilmaydi.

Statistika: `curl http://127.0.0.1:5055/stats`

Cache'ni tozalash: `rm server/cache.db*`

## 5. API

`POST /translate`

```json
{ "target": "uz", "items": [{ "id": 0, "text": "Hello" }] }
```

```json
{ "items": [{ "id": 0, "translation": "Salom" }] }
```

Bir requestda maksimum 50 element. Gemini xatosi → `502` + `{"error": "..."}`.

## Ma'lum cheklovlar

- Caption bo'lmagan videolar ishlamaydi (audio → speech-to-text yo'q).
- YouTube frontendini o'zgartirsa, `page.js` dagi caption olish qismini
  yangilash kerak bo'lishi mumkin.
- `debug=True` — faqat lokal ishlatish uchun.
