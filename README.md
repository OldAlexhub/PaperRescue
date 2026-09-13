# PaperRescue: PDF Scanner & OCR

**Scan it. Rescue it. PDF it.**

A production Android document scanner built with bare React Native + native Kotlin. Unlimited scanning, Rescue Scan (multi-frame capture fusion), on-device OCR, multi-page PDF export, and a local-only document library — completely free, no watermark, no account, no backend.

- Package: `com.oldalexhub.paperrescue`
- Platform: Android only (minSdk 24, targetSdk 36)
- Framework: Bare React Native 0.87 (New Architecture / Hermes)
- Monetization: Google Mobile Ads (AdMob) — banners + capped interstitials
- Developer: Old Alex Hub

## Why native Kotlin, not just JS

Document scanning is a computer-vision problem, so the vision-heavy work is implemented in Kotlin, called from React Native through a set of focused native modules:

| Concern | Implementation |
|---|---|
| Camera capture (Normal Scan + Rescue Scan) | CameraX, in a dedicated full-screen `ScannerActivity` (native, not a RN screen) |
| Document edge detection, perspective correction, enhancement filters | OpenCV (`org.opencv:opencv`), in `vision/DocScanCV.kt` |
| Rescue Scan multi-frame fusion | ORB feature matching + homography alignment, sharpness/glare scoring, and masked compositing — `vision/RescueFusion.kt`. Falls back to "best single frame, enhanced" if frames can't be reliably aligned; never invents detail. |
| OCR | Google ML Kit on-device text recognition — `modules/OcrModule.kt` |
| PDF export | Android's own `android.graphics.pdf.PdfDocument`, with a best-effort invisible text layer for searchability — `modules/PdfModule.kt` |
| File I/O, gallery import, sharing/"Save As" | Native modules using Android's system pickers (Photo Picker / SAF) so no broad storage permission is ever requested |

JS/TypeScript (`src/`) owns navigation, the document/library data model, settings, ads orchestration, and all screen UI.

## Project layout

```
PaperRescue/
  android/                     Native Android project
    app/src/main/java/com/oldalexhub/paperrescue/
      core/                    PaperRescuePackage (registers native modules)
      modules/                 FileSystem, Gallery, Scanner, DocumentProcessing,
                                RescueFusion, Quality, Ocr, Pdf, Share
      scanner/                 ScannerActivity + live-preview overlay
      vision/                  DocScanCV, RescueFusion, QualityAnalyzer (pure OpenCV)
      util/                    Paths, BitmapIO
  src/
    ads/                       AdMob config + frequency-capped interstitial manager
    components/                ScreenContainer, SafeScrollView, SafeBottomBar,
                                SafeAdContainer, KeyboardSafeScreen, Icon, Button, ...
    data/                      Local JSON-file document repository (library.json)
    hooks/                     useDocument, useDocumentSize
    logic/                     pageIngest.ts (capture/import → enhance → OCR → quality)
    native/                    Typed wrappers around every native module
    navigation/                React Navigation stack
    screens/                   Home, Scanner, PageReview, DocumentEditor, Export,
                                Library, OcrText, Settings, Crop
    state/                     Zustand stores (documents, settings)
    theme/                     Colors, typography, spacing
  assets/logo.png               App icon source (also used to generate all densities)
  scripts/generate_icons.py     Regenerates every launcher icon density from assets/logo.png
  store_assets/                 Play Store listing copy, data safety notes, screenshots plan
  release.py                    Windows-first build/sign/package automation
  PRIVACYPOLICY.md
```

## Local data model

Everything lives in Android app-private storage — no runtime storage permission is ever requested:

```
<filesDir>/documents/library.json          Document + page metadata (single JSON index)
<filesDir>/documents/settings.json         App settings
<filesDir>/documents/ad_state.json         Interstitial frequency-cap state
<filesDir>/documents/<docId>/pages/
    <pageId>_raw.jpg                        Uncropped capture (kept for manual re-crop)
    <pageId>_base.jpg                       Perspective-corrected, unenhanced source
    <pageId>_processed.jpg                  Current enhanced version (what you see/export)
    <pageId>_thumb.jpg                      Library thumbnail
<cacheDir>/captures, imports, exports, rescue_burst/   Transient working files, cleaned up after use
```

Full-resolution images are never base64-encoded across the JS bridge — only file paths cross it, and every image load is downsampled to a bounded working resolution (`BitmapIO.MAX_PAGE_DIMENSION` / `MAX_ANALYSIS_DIMENSION`), so a 50-page document never forces more than one full-resolution bitmap into memory at a time.

Document corners are stored as **normalized (0–1) fractions** of image width/height rather than pixel coordinates, so they stay valid across every resolution an image gets reloaded at (capture, analysis, crop, re-warp).

## Building it yourself

You need **Android Studio** (for its bundled JDK and SDK) on Windows. Everything else — locating the project, configuring `JAVA_HOME`/`ANDROID_HOME`, writing `local.properties`, generating a release signing key, building, and packaging — is handled by `release.py`:

```powershell
python release.py --check-env      # verify JDK/SDK are found before doing anything else
python release.py                  # full build: signed APK + AAB + packaged release folder
```

See `python release.py --help` for every flag (`--generate-key-only`, `--skip-build`, `--skip-screenshots`, `--screenshots-only`, `--clean`, `--no-clean`).

To run from source during development:

```powershell
npm install
npx react-native start
# in a second terminal, with an emulator/device connected:
npx react-native run-android
```

### Regenerating the app icon

If you replace `assets/logo.png`, regenerate every launcher icon density (legacy + adaptive) with:

```powershell
python scripts/generate_icons.py
```

## Known limitations

- **iOS is not configured.** This is an Android-only build per the product spec; the default `ios/` folder from the RN template is untouched and unsupported.
- **Scan Quality Score is a heuristic**, calibrated by feel from blur/glare/brightness/shadow/perspective/resolution signals (plus OCR word density when available) — it is explicitly not a scientific measurement, and the UI never claims otherwise.
- **ML Kit's on-device text recognizer does not expose a true confidence score**, so the "OCR confidence" quality factor is approximated from recognized word density rather than a native confidence value. OCR text itself is never invented — a page with no recognizable text simply reports none.
- **Searchable PDF text is best-effort**: OCR line boxes are drawn as fully transparent, width-matched text over the page image (the standard technique used by most OCR-to-PDF tools). Rendering support for invisible text layers varies slightly by PDF viewer, but the page always displays and prints correctly regardless.
