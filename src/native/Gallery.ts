import { NativeModules } from 'react-native';

interface GalleryNative {
  pickImages(maxCount: number): Promise<string[]>;
}

const NativeGallery = NativeModules.PaperRescueGallery as GalleryNative;

export const Gallery = {
  /** Opens Android's system photo picker (no storage permission required) and returns local file copies. */
  pickImages: (maxCount = 50) => NativeGallery.pickImages(maxCount),
};
