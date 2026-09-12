# YouTube Subtitle Loader

`.srt` yoki `.vtt` faylni YouTube videosiga vaqt bo'yicha sinxron ulaydigan
Chrome extension. **Server, API, internet — hech biri kerak emas**, hammasi
brauzerning o'zida ishlaydi.

```
extension/
├── manifest.json
├── lib.js         parser (SRT/VTT) + storage — content va popup ikkalasida
├── content.js     overlay, sinxronlash, drag&drop, klaviatura
├── content.css
├── popup.html
├── popup.js
└── namuna.srt     sinov uchun

ai-translator/     eski AI tarjimon versiyasi (ishlatilmaydi)
```

## O'rnatish

1. `chrome://extensions/`
2. Developer mode → ON
3. Load unpacked → `extension/` papkasini tanlang
4. YouTube sahifasini refresh qiling

## Ishlatish

YouTube videoni oching, keyin ikkitadan biri:

- Extension ikonkasini bosing → faylni tanlang
- Yoki `.srt` faylni **to'g'ridan-to'g'ri video ustiga sudrab tashlang**

Subtitle shu videoga bog'lanib saqlanadi — keyingi safar shu videoni ochsangiz
avtomatik chiqadi.

## Imkoniyatlar

| Nima | Qayerda |
|---|---|
| `.srt` / `.vtt` yuklash | popup yoki drag & drop |
| Format avtomatik aniqlash | fayl kengaytmasiga qaramaydi, ichiga qaraydi |
| Play / pause / seek sinxron | `video.currentTime` + binary search |
| Hajmi, foni, balandligi | popup slayderlari |
| Yoqish / o'chirish | popup checkbox |
| Vaqtni siljitish | popup slayder yoki `[` va `]` tugmalari (±0.5s) |
| Video almashganda | `yt-navigate-finish` + URL kuzatuvi |
| Fayl saqlanishi | `chrome.storage.local`, video ID bo'yicha, oxirgi 20 ta |

## Muhim texnik qarorlar

**Overlay `#movie_player` ichida.** `document.body` ga `position: fixed` bilan
qo'yilsa fullscreen'da **ko'rinmaydi** — fullscreen elementidan tashqaridagi
hech narsa render qilinmaydi. Shu sababli overlay player elementining ichiga
qo'yilgan va `position: absolute` ishlatilgan.

**Shrift player balandligiga nisbatan** (`fontSize` = balandlikning %i).
Fullscreen'ga o'tganda subtitle ham kattalashadi, alohida sozlash shart emas.

**`requestAnimationFrame`, `timeupdate` emas.** `timeupdate` eventi ~250ms da
bir marta ishlaydi — subtitle kechikib chiqqandek tuyuladi. rAF video kadri
bilan bir xil tezlikda yangilanadi, lekin cue o'zgarmasa DOM'ga tegilmaydi.

**Kodlashni avtomatik aniqlash.** Ko'p `.srt` fayllar UTF-8 emas
(Windows-1251 / 1254). `file.text()` har doim UTF-8 deb o'qiydi va matn
buziladi. Shuning uchun avval `TextDecoder("utf-8", {fatal: true})` sinaladi,
xato bersa 1251 → 1254 → 1252 ga o'tiladi.

**Popup ↔ content aloqasi `chrome.storage.onChanged` orqali**, `sendMessage`
emas. Shuning uchun content script hali yuklanmagan bo'lsa ham popup ishlaydi,
va sozlama o'zgarishi bir vaqtda ochilgan barcha YouTube tablarga yetadi.

**Matn xavfsiz chiqariladi.** `<i>`, `<b>`, `<u>` saqlanadi, qolgan hamma teg
(`<v Speaker>`, `<c.yellow>`, vaqt belgilari, `{\i1}`) olib tashlanadi.
`innerHTML` umuman ishlatilmaydi — subtitle fayli ishonchsiz manba.

## Sinash

```
extension/namuna.srt
```

Istalgan videoni oching, shu faylni video ustiga tashlang — 2-soniyadan
boshlab matn chiqishi kerak.
