# DSH Pad WebView

A ~50 KB Android app that opens the **DeepSeek Harness Web GUI** in the system WebView:
full-screen, fixed entry point, cookies that survive, downloads that actually land on the tablet.

> **Vibe-coded.** This project was built end-to-end by an AI agent
> ([DeepSeek Harness](https://github.com/deepseek-ai)) in one session: no manual scaffolding,
> no Gradle, no Android Studio. Reviewed and tested on a real tablet — see [Verified](#verified).

## Why

Accessing the Harness Web UI from a tablet browser (Via, Chrome…) has three recurring problems:

1. **Losing the session cookie.** Browsers love to "clear site data"; the LAN relay's
   `dsh_mobile` cookie goes with it. The page still renders from memory, but every `/api/`
   call returns 401 — messages silently stop being delivered.
2. **No full-screen.** The status bar eats the top of an already small screen.
3. **No downloads.** `present` delivery cards and `blob:` downloads do nothing in a WebView.

This app fixes all three with a deliberately small amount of code (~700 lines of Java, zero
third-party dependencies, zero Gradle).

## Features

- **Fixed entry + query params** — host, port and extra query (`k=<LAN key>`, `prts-safe=1`…)
  are stored in `SharedPreferences`; the WebView never loses them across redirects.
- **Full screen** — status bar hidden (nav bar kept so you can still swipe out), re-applied on
  focus changes and dialog dismissal.
- **Real downloads** — page-side interception of `a[download]` with `blob:`/`data:` hrefs,
  `fetch()` with credentials, then a `@JavascriptInterface` bridge writes the bytes through
  MediaStore into `Download/DSH/`. No base64 for files over 40 MB (falls back to DownloadManager).
- **Long-press to save** — WebView has no "save image" menu; long-press any image or link for a
  *Save to device?* dialog.
- **Works with `dsh-save-button`** — the npm plugin that adds a Download button to
  `present` delivery cards is fully compatible: a 12.8 MB package downloaded on-device with a
  matching SHA-256.
- **No accidental cookie loss** — no "clear data" shortcut; the one destructive action is behind
  a confirmation and explains how to re-pair.
- **Keyboard discipline** — the Harness SPA autofocuses the composer on session switch; on a tablet
  that means a keyboard popping up out of nowhere. Programmatic focus is blurred unless the user
  tapped the composer within the last 40 s. The composer is a `contenteditable` div (not a
  `<textarea>`), so the check is `isContentEditable` — a plain INPUT/TEXTAREA test silently misses it,
  which is exactly why the first attempt at this fix did nothing.
- **401 hint** — if the main document returns 401, an overlay explains that the cookie is gone and
  how to re-pair instead of showing a blank page.
- **Crash resilience** — `onRenderProcessGone` recreates the view instead of taking the app down.

## Build

No Gradle. Four steps, each one visible in the `Makefile`:

```sh
# prerequisites: JDK 17+, Android SDK with platforms;android-34 and build-tools;36.0.0
export ANDROID_HOME=$HOME/Android/sdk
make            # -> dist/dsh-pad.apk
```

1. `aapt2 compile` / `aapt2 link` → resource table + skeleton APK
2. `javac -source 8 -target 8 -bootclasspath android.jar` → class files
3. `d8 --release --min-api 26` → `classes.dex`
4. `zipalign` + `apksigner` → signed APK

`scripts/setup-sdk.sh` installs the minimal SDK (cmdline-tools + platform + build-tools).

### Build pitfalls (already handled, worth knowing)

| Symptom | Cause | Fix |
| --- | --- | --- |
| `d8: Type X defined multiple times` | make expands the class list and the shell eats `$1` in `Foo$1.class` | expand inside the recipe with `$$(find …)` |
| `d8: NullPointerException: String.length() … null` | build-tools **34.0.0** d8 bug with synthetic accessors | use build-tools **36.0.0** |
| `zip: command not found` | Arch has no zip by default | `scripts/pack-apk.py` (Python zipfile, STORED dex) |
| `Failed to read Key … end of file reached` | apksigner reads two passwords from one line | password file needs **two** lines |
| `make` re-signs forever | phony `keystore` prerequisite | order-only prerequisite `| keystore` |

## Install

```sh
adb install -r -g dist/dsh-pad.apk
```

Sideload it, or serve the APK over the LAN and download it on the tablet — both work.

First launch opens Settings: enter your Harness URL, e.g. `https://192.168.1.10:8443/`,
plus optional query (`k=<LAN key>`, `prts-safe=1`). Change it later by long-pressing the
launcher icon → *设置入口地址* (static shortcut).

## Provisioning from the desktop

```sh
adb shell am start -n net.atheris.dshpad/.MainActivity \
  --es dshpad.url 'https://192.168.1.10:8443/' \
  --es dshpad.query 'k=<LAN key>'
```

`--es dshpad.query '-'` clears the query. `--ez dshpad.diag true` dumps theme/URL state to
logcat (`grep DshPadDiag`), `--es dshpad.eval '<js>'` evaluates JavaScript in the page and logs the
result (`grep DshPadEval`) without reloading — handy when a tablet misbehaves.

## Notes

- Targeting `targetSdk 34` means user-installed CAs are **not** trusted by default;
  `res/xml/network_security_config.xml` opts in with `<certificates src="user"/>`. Without it, a
  self-signed LAN certificate fails.
- Downloads are streamed through the page (`fetch`) rather than DownloadManager because the system
  process neither trusts the user CA nor carries the WebView cookies.
- Fullscreen uses both `windowFullscreen` and a runtime `WindowInsetsController`; the runtime call
  must happen **after** `setContentView`, or some ROMs throw from `getInsetsController()`.

## Verified

Android 14 tablet (iPA2451, WebView 150): pairing, cookie persistence across process death,
full screen, theme rendering, composer keyboard rules, 12.8 MB attachment download with matching
SHA-256, long-press save.

## License

MIT. Not affiliated with DeepSeek; “Harness” is only mentioned to describe compatibility.
