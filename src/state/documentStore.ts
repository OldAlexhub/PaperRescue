import { create } from 'zustand';
import { Document } from '../types/document';
import * as repo from '../data/repository';

interface DocumentStoreState {
  items: repo.DocumentListItem[];
  loading: boolean;
  loaded: boolean;
  refresh: () => Promise<void>;
  rename: (docId: string, name: string) => Promise<void>;
  remove: (docId: string) => Promise<void>;
  duplicate: (docId: string) => Promise<Document | null>;
}

export const useDocumentStore = create<DocumentStoreState>((set, get) => ({
  items: [],
  loading: false,
  loaded: false,

  refresh: async () => {
    set({ loading: true });
    try {
      const items = await repo.listDocumentsWithFirstPage();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      console.warn('PaperRescue: failed to load documents', e);
      set({ loading: false, loaded: true });
    }
  },

  rename: async (docId, name) => {
    await repo.renameDocument(docId, name);
    await get().refresh();
  },

  remove: async docId => {
    await repo.deleteDocument(docId);
    await get().refresh();
  },

  duplicate: async docId => {
    const created = await repo.duplicateDocument(docId);
    await get().refresh();
    return created;
  },
}));
