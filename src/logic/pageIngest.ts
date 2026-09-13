import { DocumentProcessing } from '../native/DocumentProcessing';
import { Ocr } from '../native/Ocr';
import { Quality } from '../native/Quality';
import * as repo from '../data/repository';
import { getAppDirectories } from '../data/appDirs';
import { capturePath } from '../data/paths';
import { generateId } from '../utils/id';
import { AppSettings } from '../types/settings';
import { DEFAULT_ENHANCE_SETTINGS, Page, RescueReport } from '../types/document';

interface CapturedSource {
  basePath: string;
  rawPath?: string | null;
  corners?: number[] | null;
  rescue?: RescueReport | null;
}

/**
 * Persists a new page and runs the optional auto-quality-check / auto-OCR
 * passes according to settings. OCR failures never block the page from being
 * kept — they're just reported back on the page's `ocr` field as absent.
 */
export async function finalizePage(docId: string, source: CapturedSource, settings: AppSettings): Promise<Page> {
  let page = await repo.addPage(
    docId,
    { basePath: source.basePath, rawPath: source.rawPath, corners: source.corners },
    { ...DEFAULT_ENHANCE_SETTINGS, mode: settings.defaultFilter },
  );

  if (source.rescue) {
    const updated = await repo.updatePage(page.id, { rescue: source.rescue });
    if (updated) page = updated;
  }

  return rehydratePage(page, settings);
}

/** Re-runs the auto-quality-check / auto-OCR passes after a page's image files changed (e.g. Retake). */
async function rehydratePage(page: Page, settings: AppSettings): Promise<Page> {
  let current = page;
  let ocrWordCount: number | undefined;

  if (settings.autoOcr) {
    try {
      const ocrResult = await Ocr.recognize(current.processedImagePath);
      ocrWordCount = ocrResult.wordCount;
      const updated = await repo.updatePage(current.id, { ocr: { ...ocrResult, computedAt: Date.now() } });
      if (updated) current = updated;
    } catch (e) {
      console.warn('PaperRescue: OCR failed for page', current.id, e);
    }
  }
  if (settings.autoQualityCheck) {
    try {
      const qualityResult = await Quality.analyze(current.processedImagePath, ocrWordCount);
      const updated = await repo.updatePage(current.id, { quality: { ...qualityResult, computedAt: Date.now() } });
      if (updated) current = updated;
    } catch (e) {
      console.warn('PaperRescue: quality analysis failed for page', current.id, e);
    }
  }
  return current;
}

/** Retake: overwrites this page's captured image in place, preserving its position in the document. */
export async function retakePage(pageId: string, source: CapturedSource, settings: AppSettings): Promise<Page> {
  const replaced = await repo.replacePageCapture(pageId, source);
  if (!replaced) throw new Error(`Page ${pageId} not found`);
  let page = replaced;
  if (source.rescue) {
    const updated = await repo.updatePage(page.id, { rescue: source.rescue });
    if (updated) page = updated;
  }
  return rehydratePage(page, settings);
}

/** Imports a gallery photo: best-effort auto-crop to a detected document edge, otherwise used as-is. */
export async function ingestImportedImage(docId: string, importedPath: string, settings: AppSettings): Promise<Page> {
  const dirs = await getAppDirectories();
  let basePath = importedPath;
  let corners: number[] | null = null;

  try {
    const detection = await DocumentProcessing.detectDocumentCorners(importedPath);
    // Preserve even a low-confidence best candidate so manual crop starts near
    // the page instead of inventing a confidently detected full-frame quad.
    corners = detection.corners;
    if (detection.detected && detection.corners) {
      const outPath = capturePath(dirs, `${generateId()}_import.jpg`);
      const warped = await DocumentProcessing.warpPerspective(importedPath, detection.corners, outPath);
      basePath = warped.path;
    }
  } catch (e) {
    console.warn('PaperRescue: auto-crop skipped for imported image', e);
  }

  return finalizePage(docId, { basePath, rawPath: importedPath, corners }, settings);
}
