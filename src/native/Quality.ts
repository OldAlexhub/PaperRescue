import { NativeModules } from 'react-native';
import { QualityFactors, Recommendation } from '../types/document';

interface QualityNativeResult {
  score: number;
  factors: QualityFactors;
  messages: string[];
  warnings: string[];
  recommendation: Recommendation;
}

interface QualityNative {
  analyze(imagePath: string, ocrWordCount: number, hasOcrWordCount: boolean): Promise<QualityNativeResult>;
}

const NativeQuality = NativeModules.PaperRescueQuality as QualityNative;

export const Quality = {
  analyze: (imagePath: string, ocrWordCount?: number) =>
    NativeQuality.analyze(imagePath, ocrWordCount ?? 0, ocrWordCount !== undefined),
};
