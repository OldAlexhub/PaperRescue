import { NativeModules } from 'react-native';
import { OcrBlock } from '../types/document';

interface OcrNativeResult {
  text: string;
  wordCount: number;
  blocks: OcrBlock[];
  imageWidth: number;
  imageHeight: number;
}

interface OcrNative {
  recognize(imagePath: string): Promise<OcrNativeResult>;
}

const NativeOcr = NativeModules.PaperRescueOcr as OcrNative;

export const Ocr = {
  /** Rejects with E_OCR_FAILED on failure — callers must treat OCR as optional and continue. */
  recognize: (imagePath: string) => NativeOcr.recognize(imagePath),
};
