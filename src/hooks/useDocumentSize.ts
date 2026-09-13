import { useEffect, useState } from 'react';
import * as repo from '../data/repository';
import { FileSystem } from '../native/FileSystem';

const cache = new Map<string, number>();

/** Lazily sums a document's page file sizes on disk, cached per document id for the session. */
export function useDocumentSize(docId: string): number {
  const [size, setSize] = useState(cache.get(docId) ?? 0);

  useEffect(() => {
    let cancelled = false;
    if (cache.has(docId)) {
      setSize(cache.get(docId)!);
      return;
    }
    (async () => {
      try {
        const doc = await repo.getDocumentWithPages(docId);
        if (!doc) return;
        const sizes = await Promise.all(doc.pages.map(p => FileSystem.getFileSize(p.processedImagePath).catch(() => 0)));
        const total = sizes.reduce((a, b) => a + b, 0);
        cache.set(docId, total);
        if (!cancelled) setSize(total);
      } catch {
        // Non-critical — the size column just stays blank.
      }
    })();
    return () => { cancelled = true; };
  }, [docId]);

  return size;
}
