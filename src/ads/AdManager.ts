import { InterstitialAd, AdEventType } from 'react-native-google-mobile-ads';
import { AdUnitIds, InterstitialPolicy } from './adsConfig';
import { readJsonSafe, writeJson } from '../native/FileSystem';
import { getAppDirectories } from '../data/appDirs';
import { adStateJsonPath } from '../data/paths';

export type SessionKind = 'scan_saved' | 'export_completed' | 'rescue_completed';

interface AdState {
  completedSessions: number;
  sessionsSinceLastInterstitial: number;
  lastInterstitialAtMs: number | null;
  hasShownFirstInterstitial: boolean;
}

const DEFAULT_STATE: AdState = {
  completedSessions: 0,
  sessionsSinceLastInterstitial: 0,
  lastInterstitialAtMs: null,
  hasShownFirstInterstitial: false,
};

/**
 * Reusable interstitial frequency-capping. Every rule from the product spec
 * lives here so it can be tuned in one place: no ad on launch, none during
 * scanning/processing/OCR/crop/share, never on the very first completed
 * session, then roughly every N sessions after that, with a hard cooldown.
 */
class AdManagerImpl {
  private state: AdState = { ...DEFAULT_STATE };
  private stateLoaded = false;
  private interstitial: InterstitialAd | null = null;
  private interstitialLoaded = false;
  private initialized = false;

  async init(): Promise<void> {
    if (this.initialized) return;
    this.initialized = true;
    await this.loadState();
    this.preloadInterstitial();
  }

  private async loadState(): Promise<void> {
    if (this.stateLoaded) return;
    const dirs = await getAppDirectories();
    this.state = await readJsonSafe<AdState>(adStateJsonPath(dirs), DEFAULT_STATE);
    this.stateLoaded = true;
  }

  private async persistState(): Promise<void> {
    const dirs = await getAppDirectories();
    await writeJson(adStateJsonPath(dirs), this.state);
  }

  private preloadInterstitial(): void {
    const ad = InterstitialAd.createForAdRequest(AdUnitIds.interstitial, {
      requestNonPersonalizedAdsOnly: false,
    });
    this.interstitialLoaded = false;
    const unsubLoaded = ad.addAdEventListener(AdEventType.LOADED, () => {
      this.interstitialLoaded = true;
    });
    const unsubError = ad.addAdEventListener(AdEventType.ERROR, () => {
      this.interstitialLoaded = false;
      unsubLoaded();
      unsubError();
    });
    const unsubClosed = ad.addAdEventListener(AdEventType.CLOSED, () => {
      unsubLoaded();
      unsubError();
      unsubClosed();
      // Immediately queue the next one so it has time to load before it's needed again.
      this.preloadInterstitial();
    });
    this.interstitial = ad;
    try {
      ad.load();
    } catch (e) {
      this.interstitialLoaded = false;
    }
  }

  /**
   * Call this once a document session genuinely completes (saved, exported,
   * or a Rescue Scan finished). Never awaited by navigation — this only ever
   * shows an ad opportunistically and never blocks the UI.
   */
  async recordCompletedSession(_kind: SessionKind): Promise<void> {
    await this.loadState();
    this.state.completedSessions += 1;
    this.state.sessionsSinceLastInterstitial += 1;

    const isFirstSessionEver = this.state.completedSessions === 1;
    if (InterstitialPolicy.skipFirstSession && isFirstSessionEver) {
      await this.persistState();
      return;
    }

    const cooldownElapsed =
      this.state.lastInterstitialAtMs === null ||
      Date.now() - this.state.lastInterstitialAtMs >= InterstitialPolicy.minCooldownMs;
    const dueBySessionCount = this.state.sessionsSinceLastInterstitial >= InterstitialPolicy.everyNSessions;

    if (cooldownElapsed && dueBySessionCount && this.interstitialLoaded && this.interstitial) {
      try {
        await this.interstitial.show();
        this.state.lastInterstitialAtMs = Date.now();
        this.state.sessionsSinceLastInterstitial = 0;
        this.state.hasShownFirstInterstitial = true;
      } catch (e) {
        // If showing fails for any reason, just continue — never block the user.
      }
    }
    await this.persistState();
  }
}

export const AdManager = new AdManagerImpl();
