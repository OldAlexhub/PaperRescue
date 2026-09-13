# Play Store Screenshots

Real captures from a running build live in `store_assets/screenshots/`. Suggested captions for the Play Console listing:

1. **`01_home.png` — Home**
   Caption: "Scan it. Rescue it. PDF it."
   Sub-caption: "Your documents, organized and ready in one tap."

2. **`02_scanner.png` — Scanner**
   Caption: "Automatic edge detection, every time."
   Sub-caption: "Normal Scan or Rescue Scan — pick your mode and go."

3. **`03_page_review.png` — Page Review**
   Caption: "Fine-tune every page."
   Sub-caption: "Original, Enhanced, Grayscale, or B&W, plus brightness, contrast, sharpen, and noise controls."

4. **`04_document_editor.png` — Document Editor**
   Caption: "Reorder, rotate, and manage every page."
   Sub-caption: "No page limit. Ever."

5. **`05_export.png` — Export**
   Caption: "Export clean PDFs. No watermark. Ever."
   Sub-caption: "Original, Balanced, or Smaller file size — your choice, with searchable text from OCR."

Before submitting, capture a few more to round out the set (Play Console accepts up to 8): Rescue Scan's mode selector highlighted, the OCR/Text screen, and the Library list. These weren't captured in this batch because the source emulator session ended, but the app screens themselves are complete and ready to screenshot — see `PaperRescue/README.md` for how to run the app.

## Feature graphic

`store_assets/feature_graphic.png` (1024×500) is generated from the real app icon, wordmark, and an actual in-app screenshot by `scripts/generate_feature_graphic.py`. Re-run it any time after updating `assets/logo.png` or the screenshots:

```powershell
python scripts/generate_feature_graphic.py
```
