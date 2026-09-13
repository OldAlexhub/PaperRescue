import { useCallback, useEffect, useState } from 'react';
import { useFocusEffect } from '@react-navigation/native';
import { DocumentWithPages } from '../types/document';
import * as repo from '../data/repository';

/** Loads a document + its pages, refreshing whenever the screen regains focus. */
export function useDocument(docId: string) {
  const [doc, setDoc] = useState<DocumentWithPages | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    const loaded = await repo.getDocumentWithPages(docId);
    setDoc(loaded);
    setLoading(false);
  }, [docId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );

  return { document: doc, loading, refresh };
}
