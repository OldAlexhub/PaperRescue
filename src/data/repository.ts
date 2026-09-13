import { FileSystem, readJsonSafe, writeJson } from '../native/FileSystem';
import { DocumentProcessing } from '../native/DocumentProcessing';
import { getAppDirectories } from './appDirs';
import * as paths from './paths';
import { generateId } from '../utils/id';
import {
  Document,
  DocumentWithPages,
  LibraryFile,
  Page,
  EnhanceSettings,
} from '../types/document';

const EMPTY_LIBRARY: LibraryFile = { version: 1, documents: [], pages: {} };

/** Serializes every read-modify-write against library.json so concurrent calls never clobber each other. */
let queue: Promise<unknown> = Promise.resolve();
function serialized<T>(task: () => Promise<T>): Promise<T> {
  const result = queue.then(task, task);
  queue = result.catch(() => undefined);
  return result;
}

async function loadLibrary(): Promise<LibraryFile> {
  const dirs = await getAppDirectories();
  const lib = await readJsonSafe<LibraryFile>(paths.libraryJsonPath(dirs), EMPTY_LIBRARY);
  if (!lib.documents || !lib.pages) return { ...EMPTY_LIBRARY };
  return lib;
}

async function saveLibrary(lib: LibraryFile): Promise<void> {
  const dirs = await getAppDirectories();
  await writeJson(paths.libraryJsonPath(dirs), lib);
}

export async function listDocuments(): Promise<Document[]> {
  const lib = await loadLibrary();
  return [...lib.documents].sort((a, b) => b.updatedAt - a.updatedAt);
}

export interface DocumentListItem {
  document: Document;
  firstPage: Page | undefined;
}

/** One disk read for the whole list — attaches each document's first page for a thumbnail. */
export async function listDocumentsWithFirstPage(): Promise<DocumentListItem[]> {
  const lib = await loadLibrary();
  return [...lib.documents]
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .map(document => ({ document, firstPage: lib.pages[document.pageIds[0]] }));
}

export async function getDocumentWithPages(docId: string): Promise<DocumentWithPages | null> {
  const lib = await loadLibrary();
  const doc = lib.documents.find(d => d.id === docId);
  if (!doc) return null;
  const pages = doc.pageIds.map(id => lib.pages[id]).filter(Boolean) as Page[];
  return { ...doc, pages };
}

export function createDocument(name: string): Promise<Document> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const now = Date.now();
    const doc: Document = { id: generateId(), name, pageIds: [], createdAt: now, updatedAt: now };
    lib.documents.push(doc);
    await saveLibrary(lib);
    return doc;
  });
}

export function renameDocument(docId: string, name: string): Promise<void> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const doc = lib.documents.find(d => d.id === docId);
    if (!doc) return;
    doc.name = name;
    doc.updatedAt = Date.now();
    await saveLibrary(lib);
  });
}

export function touchDocument(docId: string): Promise<void> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const doc = lib.documents.find(d => d.id === docId);
    if (!doc) return;
    doc.updatedAt = Date.now();
    await saveLibrary(lib);
  });
}

export function deleteDocument(docId: string): Promise<void> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const doc = lib.documents.find(d => d.id === docId);
    if (!doc) return;
    doc.pageIds.forEach(id => delete lib.pages[id]);
    lib.documents = lib.documents.filter(d => d.id !== docId);
    await saveLibrary(lib);
    const dirs = await getAppDirectories();
    await FileSystem.deleteDirectory(paths.documentDir(dirs, docId));
  });
}

export function duplicateDocument(docId: string): Promise<Document | null> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const doc = lib.documents.find(d => d.id === docId);
    if (!doc) return null;
    const dirs = await getAppDirectories();
    const newDocId = generateId();
    const now = Date.now();
    const newPageIds: string[] = [];

    for (const oldPageId of doc.pageIds) {
      const oldPage = lib.pages[oldPageId];
      if (!oldPage) continue;
      const newPageId = generateId();
      const newBase = paths.pageBasePath(dirs, newDocId, newPageId);
      const newProcessed = paths.pageProcessedPath(dirs, newDocId, newPageId);
      const newThumb = paths.pageThumbPath(dirs, newDocId, newPageId);
      await FileSystem.copyFile(oldPage.baseImagePath, newBase);
      await FileSystem.copyFile(oldPage.processedImagePath, newProcessed);
      await FileSystem.copyFile(oldPage.thumbnailPath, newThumb);
      let newRaw: string | null = null;
      if (oldPage.rawImagePath) {
        newRaw = paths.pageRawPath(dirs, newDocId, newPageId);
        await FileSystem.copyFile(oldPage.rawImagePath, newRaw);
      }
      lib.pages[newPageId] = {
        ...oldPage,
        id: newPageId,
        rawImagePath: newRaw,
        baseImagePath: newBase,
        processedImagePath: newProcessed,
        thumbnailPath: newThumb,
        createdAt: now,
        updatedAt: now,
      };
      newPageIds.push(newPageId);
    }

    const newDoc: Document = {
      id: newDocId,
      name: `${doc.name} copy`,
      pageIds: newPageIds,
      createdAt: now,
      updatedAt: now,
    };
    lib.documents.push(newDoc);
    await saveLibrary(lib);
    return newDoc;
  });
}

export interface NewPageSource {
  /** Perspective-corrected, unenhanced image straight from the scanner/importer. */
  basePath: string;
  rawPath?: string | null;
  corners?: number[] | null;
}

/** Registers a freshly captured/imported page: copies it into the document's folder, builds a thumbnail, persists it. */
export function addPage(docId: string, source: NewPageSource, enhance: EnhanceSettings): Promise<Page> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const doc = lib.documents.find(d => d.id === docId);
    if (!doc) throw new Error(`Document ${docId} not found`);

    const dirs = await getAppDirectories();
    const pageId = generateId();
    const basePath = paths.pageBasePath(dirs, docId, pageId);
    const processedPath = paths.pageProcessedPath(dirs, docId, pageId);
    const thumbPath = paths.pageThumbPath(dirs, docId, pageId);

    await FileSystem.copyFile(source.basePath, basePath);
    let rawPath: string | null = null;
    if (source.rawPath) {
      rawPath = paths.pageRawPath(dirs, docId, pageId);
      await FileSystem.copyFile(source.rawPath, rawPath);
    }
    await DocumentProcessing.enhance(basePath, processedPath, enhance);
    await DocumentProcessing.generateThumbnail(processedPath, thumbPath, 360);

    const now = Date.now();
    const page: Page = {
      id: pageId,
      rawImagePath: rawPath,
      baseImagePath: basePath,
      processedImagePath: processedPath,
      thumbnailPath: thumbPath,
      corners: source.corners ?? null,
      rotation: 0,
      enhance,
      quality: null,
      ocr: null,
      rescue: null,
      createdAt: now,
      updatedAt: now,
    };
    lib.pages[pageId] = page;
    doc.pageIds.push(pageId);
    doc.updatedAt = now;
    await saveLibrary(lib);
    return page;
  });
}

/** Overwrites an existing page's image files in place (used by "Retake") so page order/id never changes. */
export function replacePageCapture(
  pageId: string,
  source: { basePath: string; rawPath?: string | null; corners?: number[] | null },
): Promise<Page | null> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const page = lib.pages[pageId];
    if (!page) return null;

    await FileSystem.copyFile(source.basePath, page.baseImagePath);
    if (source.rawPath) {
      const dirs = await getAppDirectories();
      const owner = lib.documents.find(d => d.pageIds.includes(pageId));
      const rawPath = owner ? paths.pageRawPath(dirs, owner.id, pageId) : page.rawImagePath ?? source.rawPath;
      await FileSystem.copyFile(source.rawPath, rawPath);
      page.rawImagePath = rawPath;
    }
    await DocumentProcessing.enhance(page.baseImagePath, page.processedImagePath, page.enhance);
    await DocumentProcessing.generateThumbnail(page.processedImagePath, page.thumbnailPath, 360);

    page.corners = source.corners ?? null;
    page.rotation = 0;
    page.quality = null;
    page.ocr = null;
    page.rescue = null;
    page.updatedAt = Date.now();
    lib.pages[pageId] = page;
    await saveLibrary(lib);
    return page;
  });
}

export function updatePage(pageId: string, patch: Partial<Page>): Promise<Page | null> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const page = lib.pages[pageId];
    if (!page) return null;
    const updated: Page = { ...page, ...patch, id: page.id, updatedAt: Date.now() };
    lib.pages[pageId] = updated;
    const owner = lib.documents.find(d => d.pageIds.includes(pageId));
    if (owner) owner.updatedAt = Date.now();
    await saveLibrary(lib);
    return updated;
  });
}

export function deletePage(docId: string, pageId: string): Promise<{ documentDeleted: boolean }> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const doc = lib.documents.find(d => d.id === docId);
    if (!doc) return { documentDeleted: false };
    const page = lib.pages[pageId];
    doc.pageIds = doc.pageIds.filter(id => id !== pageId);
    delete lib.pages[pageId];
    doc.updatedAt = Date.now();

    let documentDeleted = false;
    if (doc.pageIds.length === 0) {
      lib.documents = lib.documents.filter(d => d.id !== docId);
      documentDeleted = true;
    }
    await saveLibrary(lib);

    if (page) {
      await Promise.all([
        FileSystem.deleteFile(page.baseImagePath),
        FileSystem.deleteFile(page.processedImagePath),
        FileSystem.deleteFile(page.thumbnailPath),
        page.rawImagePath ? FileSystem.deleteFile(page.rawImagePath) : Promise.resolve(true),
      ]);
    }
    if (documentDeleted) {
      const dirs = await getAppDirectories();
      await FileSystem.deleteDirectory(paths.documentDir(dirs, docId));
    }
    return { documentDeleted };
  });
}

export function duplicatePage(docId: string, pageId: string): Promise<Page | null> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const doc = lib.documents.find(d => d.id === docId);
    const original = lib.pages[pageId];
    if (!doc || !original) return null;

    const dirs = await getAppDirectories();
    const newPageId = generateId();
    const newBase = paths.pageBasePath(dirs, docId, newPageId);
    const newProcessed = paths.pageProcessedPath(dirs, docId, newPageId);
    const newThumb = paths.pageThumbPath(dirs, docId, newPageId);
    await FileSystem.copyFile(original.baseImagePath, newBase);
    await FileSystem.copyFile(original.processedImagePath, newProcessed);
    await FileSystem.copyFile(original.thumbnailPath, newThumb);
    let newRaw: string | null = null;
    if (original.rawImagePath) {
      newRaw = paths.pageRawPath(dirs, docId, newPageId);
      await FileSystem.copyFile(original.rawImagePath, newRaw);
    }

    const now = Date.now();
    const newPage: Page = {
      ...original,
      id: newPageId,
      rawImagePath: newRaw,
      baseImagePath: newBase,
      processedImagePath: newProcessed,
      thumbnailPath: newThumb,
      createdAt: now,
      updatedAt: now,
    };
    lib.pages[newPageId] = newPage;
    const index = doc.pageIds.indexOf(pageId);
    doc.pageIds.splice(index + 1, 0, newPageId);
    doc.updatedAt = now;
    await saveLibrary(lib);
    return newPage;
  });
}

export function reorderPages(docId: string, orderedPageIds: string[]): Promise<void> {
  return serialized(async () => {
    const lib = await loadLibrary();
    const doc = lib.documents.find(d => d.id === docId);
    if (!doc) return;
    const valid = orderedPageIds.filter(id => doc.pageIds.includes(id));
    if (valid.length === doc.pageIds.length) {
      doc.pageIds = valid;
      doc.updatedAt = Date.now();
      await saveLibrary(lib);
    }
  });
}

export async function clearAllData(): Promise<void> {
  const dirs = await getAppDirectories();
  await FileSystem.deleteDirectory(dirs.documentsDir);
  await FileSystem.makeDirectory(dirs.documentsDir);
  await FileSystem.deleteDirectory(dirs.capturesDir);
  await FileSystem.deleteDirectory(dirs.importsDir);
  await FileSystem.deleteDirectory(dirs.exportsDir);
}
