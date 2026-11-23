# WatchDocs

**Read PDF, Word and Excel documents on your Wear OS watch. Free, no ads, no subscription.**

Send a document from your phone, and it's stored on the watch, so you can read it offline.

- **PDF:** drag to move, pinch or double-tap to zoom (sharp text up to 6×), turn the crown to scroll, tap for page controls and go-to-page. It remembers where you left off.
- **Word (.docx):** text, headings, lists and tables, with adjustable text size.
- **Excel (.xlsx):** a scrollable grid with all sheets.

Works with Wear OS 3+ watches (Pixel Watch, Galaxy Watch 4 and newer, …) paired with an Android phone.

---

## Install

### 📱 Phone
On your Android phone, open this link and install the file:

**https://github.com/TwipleK/watchdocs/releases/latest/download/WatchDocs-phone.apk**

(Allow "install unknown apps" for your browser when Android asks.)

### ⌚ Watch: easiest with a Windows PC
Watches can't install downloaded apps on their own, so a PC sends it over Wi-Fi. The installer does everything for you: it downloads Google's adb tool and the app, then guides you through pairing.

1. Download **[install-windows.bat](https://github.com/TwipleK/watchdocs/releases/latest/download/install-windows.bat)** and double-click it.
   If Windows SmartScreen warns you, click *More info → Run anyway*.
   You can also paste this into PowerShell instead:
   ```powershell
   irm https://raw.githubusercontent.com/TwipleK/watchdocs/main/install.ps1 | iex
   ```
2. Follow the steps it shows. In short:
   - On the watch: **Settings → System → About → Versions**, tap **Build number** 7×.
   - **Settings → Developer options → Wireless debugging** on → **Pair new device**.
   - Type the IP:port and the code from the watch into the installer.

The installer can also install the phone app the same way.

### ⌚ Watch: without a PC
Use a phone app that installs APKs onto a watch over Wi-Fi, such as *Bugjaeger* or *Wear Installer 2* from the Play Store. Point it at [WatchDocs-watch.apk](https://github.com/TwipleK/watchdocs/releases/latest/download/WatchDocs-watch.apk) and follow its instructions; you'll also need to turn on Wireless debugging on the watch.

### Mac / Linux
Install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools), then:
```sh
adb pair WATCH_IP:PAIR_PORT CODE      # from "Pair new device"
adb connect WATCH_IP:PORT             # from the main Wireless debugging screen
adb install WatchDocs-watch.apk
```

### Updating
Run the installer again. It always installs the latest release.

---

## Using it
1. Open **WatchDocs on the phone** and tap **Send documents to watch**, or use **Share → WatchDocs** from any app (Files, Gmail, Drive…).
2. Open **WatchDocs on the watch** and tap a document.
3. Long-press a document in the watch list to delete it.

**Limitations:** password-protected PDFs aren't supported. Word shows no images. Excel shows values without number formats (dates appear as numbers) and at most 2000 rows × 60 columns.

---

## Building from source
Requires JDK 17 and the Android SDK (platform 35).
```powershell
.\gradlew.bat assembleRelease
```
- `wear/` is the watch app (Jetpack Compose for Wear OS, PdfRenderer, its own DOCX/XLSX parser).
- `mobile/` is the phone app, which sends files over the Wearable Data Layer (`ChannelClient`).

The phone and watch apps share the applicationId `com.watchdocs.app` and must be signed with the same key to talk to each other. Release builds use `../signing/keystore.properties` if it exists, and otherwise fall back to your debug key.

Maintainer scripts: `update.bat` builds and installs on all connected devices, and `release.ps1` publishes a GitHub release.

## License
MIT
