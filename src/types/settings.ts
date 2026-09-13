import { FilterMode, PdfQuality } from './document';

export interface AppSettings {
  defaultFilter: FilterMode;
  defaultPdfQuality: PdfQuality;
  autoOcr: boolean;
  autoQualityCheck: boolean;
}

export const DEFAULT_SETTINGS: AppSettings = {
  defaultFilter: 'enhanced',
  defaultPdfQuality: 'balanced',
  autoOcr: true,
  autoQualityCheck: true,
};
