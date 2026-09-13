import { readJsonSafe, writeJson } from '../native/FileSystem';
import { getAppDirectories } from './appDirs';
import { settingsJsonPath } from './paths';
import { AppSettings, DEFAULT_SETTINGS } from '../types/settings';

export async function loadSettings(): Promise<AppSettings> {
  const dirs = await getAppDirectories();
  const stored = await readJsonSafe<Partial<AppSettings>>(settingsJsonPath(dirs), {});
  return { ...DEFAULT_SETTINGS, ...stored };
}

export async function saveSettings(settings: AppSettings): Promise<void> {
  const dirs = await getAppDirectories();
  await writeJson(settingsJsonPath(dirs), settings);
}
