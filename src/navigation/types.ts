import { ScanMode } from '../native/Scanner';

export type RootStackParamList = {
  Home: undefined;
  Library: undefined;
  Scanner: { docId: string; pageNumber: number; mode: ScanMode };
  PageReview: { docId: string; pageId: string; adjustCrop?: boolean };
  DocumentEditor: { docId: string };
  Export: { docId: string };
  OcrText: { docId: string };
  Settings: undefined;
  Crop: { docId: string; pageId: string };
};
