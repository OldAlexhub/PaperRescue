import { NativeModules } from 'react-native';

export interface SaveAsResult {
  cancelled: boolean;
  savedUri?: string;
}

interface ShareNative {
  shareFile(path: string, mimeType: string, title: string): Promise<boolean>;
  shareFiles(paths: string[], mimeType: string, title: string): Promise<boolean>;
  openFile(path: string, mimeType: string): Promise<boolean>;
  saveAs(sourcePath: string, suggestedName: string, mimeType: string): Promise<SaveAsResult>;
}

const NativeShare = NativeModules.PaperRescueShare as ShareNative;

export const Share = {
  shareFile: (path: string, mimeType: string, title = 'Share') => NativeShare.shareFile(path, mimeType, title),
  shareFiles: (paths: string[], mimeType: string, title = 'Share') => NativeShare.shareFiles(paths, mimeType, title),
  openFile: (path: string, mimeType: string) => NativeShare.openFile(path, mimeType),
  /** Opens Android's "Save As" system picker so the user chooses exactly where the file goes. */
  saveAs: (sourcePath: string, suggestedName: string, mimeType: string) =>
    NativeShare.saveAs(sourcePath, suggestedName, mimeType),
};
