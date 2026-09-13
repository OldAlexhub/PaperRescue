import { TestIds } from 'react-native-google-mobile-ads';

/**
 * Centralized ad configuration. Production IDs are used only in release
 * builds; __DEV__ always falls back to Google's official test IDs so
 * development never serves (or accidentally clicks) real ads.
 */
const PROD_BANNER_ID = 'ca-app-pub-7831002909037560/2053448650';
const PROD_INTERSTITIAL_ID = 'ca-app-pub-7831002909037560/5220511359';

export const AdUnitIds = {
  banner: __DEV__ ? TestIds.ADAPTIVE_BANNER : PROD_BANNER_ID,
  interstitial: __DEV__ ? TestIds.INTERSTITIAL : PROD_INTERSTITIAL_ID,
};

/** Frequency-capping knobs — tune here, nowhere else. */
export const InterstitialPolicy = {
  /** Show roughly every N completed document sessions (save/export/Rescue Scan finish). */
  everyNSessions: 2,
  /** Never show an interstitial on the very first completed session of the app's lifetime. */
  skipFirstSession: true,
  /** Minimum time between two interstitials, regardless of session count. */
  minCooldownMs: 90_000,
};
