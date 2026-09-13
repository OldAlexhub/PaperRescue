import { NativeModules } from 'react-native';
import { PdfQuality } from '../types/document';
import { OcrBlock } from '../types/document';

export interface PdfPageInput {
  imagePath: string;
  ocrBlocks?: OcrBlock[];
  ocrImageWidth?: number;
  ocrImageHeight?: number;
}

export interface PdfBuildResult {
  path: string;
  sizeBytes: number;
  pageCount: number;
}

interface PdfNative {
  buildPdf(pages: PdfPageInput[], quality: PdfQuality, outputPath: string, searchable: boolean): Promise<PdfBuildResult>;
}

const NativePdf = NativeModules.PaperRescuePdf as PdfNative;

export const Pdf = {
  buildPdf: (pages: PdfPageInput[], quality: PdfQuality, outputPath: string, searchable: boolean) =>
    NativePdf.buildPdf(pages, quality, outputPath, searchable),
};
