export type FilterMode = 'original' | 'enhanced' | 'grayscale' | 'bw';
export type PdfQuality = 'original' | 'balanced' | 'smaller';
export type Recommendation = 'good' | 'retake_recommended' | 'rescue_recommended';

export interface EnhanceSettings {
  mode: FilterMode;
  brightness: number; // -100..100
  contrast: number; // -100..100
  sharpen: number; // 0..100
  denoise: number; // 0..100
  whitenBackground: boolean;
  reduceShadow: boolean;
}

export const DEFAULT_ENHANCE_SETTINGS: EnhanceSettings = {
  mode: 'enhanced',
  brightness: 0,
  contrast: 0,
  sharpen: 0,
  denoise: 0,
  whitenBackground: true,
  reduceShadow: true,
};

export interface QualityFactors {
  blur: number;
  glare: number;
  brightness: number;
  shadow: number;
  perspective: number;
  completeness: number;
  resolution: number;
  ocrConfidence: number | null;
}

export interface QualityReport {
  score: number;
  factors: QualityFactors;
  messages: string[];
  warnings: string[];
  recommendation: Recommendation;
  computedAt: number;
}

export interface OcrBlock {
  text: string;
  box: [number, number, number, number];
}

export interface OcrResult {
  text: string;
  wordCount: number;
  blocks: OcrBlock[];
  imageWidth: number;
  imageHeight: number;
  computedAt: number;
}

export interface RescueReport {
  framesCaptured: number;
  framesUsable: number;
  alignmentConfidence: number;
  fallbackUsed: boolean;
  glareRegionsFused: boolean;
}

export interface Page {
  id: string;
  /** Raw, perspective-uncropped capture — kept for manual re-crop. May be absent for imported images. */
  rawImagePath: string | null;
  /** Perspective-corrected, unenhanced source used as the base for every filter re-render. */
  baseImagePath: string;
  /** Current enhanced image the user sees and that gets exported. */
  processedImagePath: string;
  thumbnailPath: string;
  /** 4 corners as [x0,y0,x1,y1,...] fractions (0..1) of rawImagePath's width/height. */
  corners: number[] | null;
  rotation: 0 | 90 | 180 | 270;
  enhance: EnhanceSettings;
  quality: QualityReport | null;
  ocr: OcrResult | null;
  rescue: RescueReport | null;
  createdAt: number;
  updatedAt: number;
}

export interface Document {
  id: string;
  name: string;
  pageIds: string[];
  createdAt: number;
  updatedAt: number;
}

export interface DocumentWithPages extends Document {
  pages: Page[];
}

export interface LibraryFile {
  version: 1;
  documents: Document[];
  pages: Record<string, Page>;
}
