import { FileSystem, AppDirectories } from '../native/FileSystem';

let cached: AppDirectories | null = null;
let pending: Promise<AppDirectories> | null = null;

/** App-private directories never change during a process's lifetime, so we fetch them once. */
export function getAppDirectories(): Promise<AppDirectories> {
  if (cached) return Promise.resolve(cached);
  if (!pending) {
    pending = FileSystem.getAppDirectories().then(dirs => {
      cached = dirs;
      return dirs;
    });
  }
  return pending;
}
