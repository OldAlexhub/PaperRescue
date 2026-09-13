import { NativeModules } from 'react-native';
import { RescueReport } from '../types/document';

export type ScanMode = 'single' | 'rescue';

export interface ScanCaptured {
  status: 'captured';
  rawImagePath: string;
  correctedImagePath: string;
  corners: number[];
  rescueReport?: RescueReport;
}
export interface ScanCancelled {
  status: 'cancelled';
}
export interface ScanUseGallery {
  status: 'use_gallery';
}
export type ScanResult = ScanCaptured | ScanCancelled | ScanUseGallery;

interface ScannerNative {
  startScan(mode: ScanMode, pageNumber: number): Promise<ScanResult>;
}

const NativeScanner = NativeModules.PaperRescueScanner as ScannerNative;

export const Scanner = {
  /** Launches the full-screen native camera. Rejects with a code like CAMERA_PERMISSION_DENIED / CAMERA_UNAVAILABLE. */
  startScan: (mode: ScanMode, pageNumber: number) => NativeScanner.startScan(mode, pageNumber),
};
