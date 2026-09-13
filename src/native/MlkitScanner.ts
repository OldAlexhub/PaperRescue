import { NativeModules } from 'react-native';

export interface MlkitScanResult {
  status: 'captured' | 'cancelled';
  pages?: string[];
}

interface MlkitScannerNative {
  startScan(): Promise<MlkitScanResult>;
}

const NativeMlkitScanner = NativeModules.PaperRescueMlkitScanner as MlkitScannerNative;

export const MlkitScanner = {
  /**
   * Launches Google's ML Kit Document Scanner UI (live edge detection,
   * auto-capture, crop/rotate/filters, multi-page session). Rejects with
   * E_MLKIT_SCANNER_UNAVAILABLE on unsupported devices or any Play Services
   * failure — callers should fall back to the custom Scanner screen.
   */
  startScan: () => NativeMlkitScanner.startScan(),
};
