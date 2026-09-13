import { NativeModules } from 'react-native';

export interface AppDirectories {
  documentsDir: string;
  cacheDir: string;
  capturesDir: string;
  importsDir: string;
  exportsDir: string;
}

export interface StorageInfo {
  freeBytes: number;
  totalBytes: number;
}

interface FileSystemNative {
  getAppDirectories(): Promise<AppDirectories>;
  writeTextFile(path: string, content: string): Promise<boolean>;
  readTextFile(path: string): Promise<string>;
  fileExists(path: string): Promise<boolean>;
  deleteFile(path: string): Promise<boolean>;
  deleteDirectory(path: string): Promise<boolean>;
  makeDirectory(path: string): Promise<boolean>;
  moveFile(source: string, destination: string): Promise<boolean>;
  copyFile(source: string, destination: string): Promise<boolean>;
  listDirectory(path: string): Promise<string[]>;
  getFileSize(path: string): Promise<number>;
  getStorageInfo(): Promise<StorageInfo>;
}

const NativeFS = NativeModules.PaperRescueFileSystem as FileSystemNative;

export const FileSystem = {
  getAppDirectories: () => NativeFS.getAppDirectories(),
  writeTextFile: (path: string, content: string) => NativeFS.writeTextFile(path, content),
  readTextFile: (path: string) => NativeFS.readTextFile(path),
  fileExists: (path: string) => NativeFS.fileExists(path),
  deleteFile: (path: string) => NativeFS.deleteFile(path),
  deleteDirectory: (path: string) => NativeFS.deleteDirectory(path),
  makeDirectory: (path: string) => NativeFS.makeDirectory(path),
  moveFile: (source: string, destination: string) => NativeFS.moveFile(source, destination),
  copyFile: (source: string, destination: string) => NativeFS.copyFile(source, destination),
  listDirectory: (path: string) => NativeFS.listDirectory(path),
  getFileSize: (path: string) => NativeFS.getFileSize(path),
  getStorageInfo: () => NativeFS.getStorageInfo(),
};

/** Reads JSON from disk, returning `fallback` if the file is missing, empty, or corrupt. */
export async function readJsonSafe<T>(path: string, fallback: T): Promise<T> {
  try {
    const exists = await FileSystem.fileExists(path);
    if (!exists) return fallback;
    const raw = await FileSystem.readTextFile(path);
    if (!raw || !raw.trim()) return fallback;
    return JSON.parse(raw) as T;
  } catch (e) {
    console.warn(`PaperRescue: failed to read/parse ${path}, using fallback.`, e);
    return fallback;
  }
}

export async function writeJson(path: string, value: unknown): Promise<void> {
  await FileSystem.writeTextFile(path, JSON.stringify(value));
}
