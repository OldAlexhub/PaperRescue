import { AppDirectories } from '../native/FileSystem';

/**
 * Mirrors android/.../util/Paths.kt's layout. All of this lives under
 * app-private storage, so no runtime storage permission is ever required.
 *
 *   documentsDir/library.json
 *   documentsDir/settings.json
 *   documentsDir/ad_state.json
 *   documentsDir/<docId>/pages/<pageId>_raw.jpg
 *   documentsDir/<docId>/pages/<pageId>_base.jpg
 *   documentsDir/<docId>/pages/<pageId>_processed.jpg
 *   documentsDir/<docId>/pages/<pageId>_thumb.jpg
 */
export const libraryJsonPath = (dirs: AppDirectories) => `${dirs.documentsDir}/library.json`;
export const settingsJsonPath = (dirs: AppDirectories) => `${dirs.documentsDir}/settings.json`;
export const adStateJsonPath = (dirs: AppDirectories) => `${dirs.documentsDir}/ad_state.json`;

export const documentDir = (dirs: AppDirectories, docId: string) => `${dirs.documentsDir}/${docId}`;
export const pagesDir = (dirs: AppDirectories, docId: string) => `${documentDir(dirs, docId)}/pages`;

export const pageRawPath = (dirs: AppDirectories, docId: string, pageId: string) =>
  `${pagesDir(dirs, docId)}/${pageId}_raw.jpg`;
export const pageBasePath = (dirs: AppDirectories, docId: string, pageId: string) =>
  `${pagesDir(dirs, docId)}/${pageId}_base.jpg`;
export const pageProcessedPath = (dirs: AppDirectories, docId: string, pageId: string) =>
  `${pagesDir(dirs, docId)}/${pageId}_processed.jpg`;
export const pageThumbPath = (dirs: AppDirectories, docId: string, pageId: string) =>
  `${pagesDir(dirs, docId)}/${pageId}_thumb.jpg`;

export const exportedPdfPath = (dirs: AppDirectories, docId: string, fileNameSafe: string) =>
  `${dirs.exportsDir}/${fileNameSafe}_${docId.slice(0, 8)}.pdf`;
export const exportedJpegPath = (dirs: AppDirectories, baseName: string, index: number) =>
  `${dirs.exportsDir}/${baseName}_${String(index + 1).padStart(2, '0')}.jpg`;

export const capturePath = (dirs: AppDirectories, fileName: string) => `${dirs.capturesDir}/${fileName}`;
