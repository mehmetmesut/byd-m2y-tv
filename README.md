# M2Y TV

**A lightweight IPTV player for the BYD Seal U DM-i head unit.**
Developed by **Yeşil Dönüşüm Mühendisi - M2Y** and shared free for every BYD owner.

[Türkçe açıklama aşağıda](#türkçe) · Downloads: **[byd.mehmetmesut.com](https://byd.mehmetmesut.com)**

---

## Why it exists

The IPTV players in the stores are built for TV boxes and phones: they carry ads, Google Play licensing or analytics, and slow down with large provider lists. M2Y TV is written from scratch for one screen and one car:

| Head unit | Design decision |
|---|---|
| Android 10 (API 29), arm64, no Google services | Media3 ExoPlayer + OkHttp only; no Google, Firebase, billing, ads or tracking |
| SM6125 CPU, 1920×1080 at 240 dpi | Single-activity Views UI, recycled lists, 56 dp touch targets; about 1.75 MB APK |
| Provider lists with tens of thousands of items | Streaming parsers into SQLite with batched inserts; paging, so 60,000 films scroll without loading them all |
| Shared car, saved logins | Playlist addresses and Xtream logins are sealed with an Android Keystore key and masked in logs |

## Features

- **Sources:** M3U / M3U8 playlists by URL and **Xtream Codes** logins; several playlists, one selected at a time; refresh on demand.
- **Live TV, Movies, Series** with categories, favourites (long press) and Turkish-aware search.
- **EPG:** XMLTV guide (from the playlist or Xtream) for the current and next programme.
- **Playback:** preview box and full screen, previous/next channel, −10 s / +30 s, and playback speed for films and episodes (1× → 1.25× → 1.5× → 0.5× → 0.75×; live is always 1×).
- **Errors:** a stalled stream gives up within about 30 s with a tap-to-retry message instead of hanging.

## Install

```
adb connect <car-ip>:5555
adb install -r M2YTV_1.25.07.68.0.apk
```
Then open M2Y TV and add a playlist in **Ayarlar** (Settings). You need your own IPTV subscription; M2Y TV ships no channels.

## Build

```
./gradlew test assembleRelease   # JDK 21, Android SDK
```
Signing reads a properties file outside the repository (`m2y.signingProps` in `local.properties`). `tools/mock_iptv.py` serves a local test playlist for the emulator.

## Licence and credits

- © 2026 Yeşil Dönüşüm Mühendisi - M2Y. Released under the [GNU GPL v3.0](LICENSE).
- AndroidX Media3 (ExoPlayer), AndroidX RecyclerView — Apache-2.0, The Android Open Source Project.
- OkHttp — Apache-2.0, Square, Inc. · kotlinx.coroutines — Apache-2.0, JetBrains.

**Not affiliated with BYD or any IPTV provider.** Use only with streams you have the right to watch.

---

## Türkçe

**BYD Seal U DM-i multimedya ekranı için hafif IPTV oynatıcısı.**
**Yeşil Dönüşüm Mühendisi - M2Y** tarafından kendi aracım için geliştirildi ve tüm BYD sahiplerinin kullanımına ücretsiz sunuldu.

- M3U / M3U8 bağlantısı ve **Xtream Codes** girişi; birden çok liste, istendiğinde yenileme.
- Canlı TV, Filmler, Diziler; kategoriler, favoriler (basılı tutarak) ve Türkçe harflere duyarlı arama.
- XMLTV yayın akışı (şimdiki ve sıradaki program).
- Önizleme ve tam ekran, önceki/sonraki kanal, −10 sn / +30 sn; film ve dizide oynatma hızı (canlı yayında her zaman 1x).
- On binlerce öğelik listeler SQLite'a akışla yazılır, sayfalı gösterilir; bellek 33–43 MB (emülatörde ölçüldü).
- Liste adresleri ve Xtream girişleri Android Keystore ile şifreli saklanır.
- Google hizmeti, reklam ve izleme yok; APK yaklaşık 1,75 MB.

Kurulum: `adb install -r M2YTV_1.25.07.68.0.apk`, ardından **Ayarlar**'dan kendi IPTV listenizi ekleyin; uygulama kanal içermez.
Lisans: GPL-3.0. BYD ya da herhangi bir IPTV sağlayıcısıyla bağlantılı değildir.
