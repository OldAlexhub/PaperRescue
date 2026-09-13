import { NativeModules } from 'react-native';
import { EnhanceSettings } from '../types/document';

interface CornersResult {
  corners: number[] | null;
  detected: boolean;
  confidence: number;
  source: string;
  analyzedWidth: number;
  analyzedHeight: number;
}
interface PathResult {
  path: string;
}
interface WarpResult extends PathResult {
  width: number;
  height: number;
}
interface JpegExportResult extends PathResult {
  sizeBytes: number;
}

interface ImagingNative {
  detectDocumentCorners(imagePath: string): Promise<CornersResult>;
  warpPerspective(imagePath: string, corners: number[], outputPath: string): Promise<WarpResult>;
  enhance(imagePath: string, outputPath: string, options: EnhanceSettings): Promise<PathResult>;
  autoRescue(imagePath: string, outputPath: string): Promise<PathResult>;
  rotateImage(imagePath: string, outputPath: string, degrees: number): Promise<PathResult>;
  generateThumbnail(imagePath: string, outputPath: string, maxDimension: number): Promise<PathResult>;
  exportJpeg(imagePath: string, outputPath: string, quality: number): Promise<JpegExportResult>;
}

const NativeImaging = NativeModules.PaperRescueImaging as ImagingNative;

export const DocumentProcessing = {
  detectDocumentCorners: (imagePath: string) => NativeImaging.detectDocumentCorners(imagePath),
  warpPerspective: (imagePath: string, corners: number[], outputPath: string) =>
    NativeImaging.warpPerspective(imagePath, corners, outputPath),
  enhance: (imagePath: string, outputPath: string, options: EnhanceSettings) =>
    NativeImaging.enhance(imagePath, outputPath, options),
  autoRescue: (imagePath: string, outputPath: string) => NativeImaging.autoRescue(imagePath, outputPath),
  rotateImage: (imagePath: string, outputPath: string, degrees: number) =>
    NativeImaging.rotateImage(imagePath, outputPath, degrees),
  generateThumbnail: (imagePath: string, outputPath: string, maxDimension = 320) =>
    NativeImaging.generateThumbnail(imagePath, outputPath, maxDimension),
  exportJpeg: (imagePath: string, outputPath: string, quality = 92) =>
    NativeImaging.exportJpeg(imagePath, outputPath, quality),
};
