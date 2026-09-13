import { create } from 'zustand';
import { AppSettings, DEFAULT_SETTINGS } from '../types/settings';
import { loadSettings, saveSettings } from '../data/settingsRepository';

interface SettingsStoreState {
  settings: AppSettings;
  loaded: boolean;
  load: () => Promise<void>;
  update: (patch: Partial<AppSettings>) => Promise<void>;
}

export const useSettingsStore = create<SettingsStoreState>((set, get) => ({
  settings: DEFAULT_SETTINGS,
  loaded: false,

  load: async () => {
    const settings = await loadSettings();
    set({ settings, loaded: true });
  },

  update: async patch => {
    const next = { ...get().settings, ...patch };
    set({ settings: next });
    await saveSettings(next);
  },
}));
