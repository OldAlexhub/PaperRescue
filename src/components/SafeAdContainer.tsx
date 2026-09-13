import React, { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { BannerAd, BannerAdSize } from 'react-native-google-mobile-ads';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AdUnitIds } from '../ads/adsConfig';
import { spacing } from '../theme/spacing';

interface Props {
  /** Pin above the OS gesture/nav bar when this container sits at the physical bottom of the screen. */
  pinnedToBottom?: boolean;
}

/**
 * Anchored adaptive banner slot used on Home, Library, OCR/Text, Settings and
 * the completed-document preview. Reserves no space until an ad is loaded and
 * collapses back to nothing if the load fails — it never leaves a dead box.
 */
export function SafeAdContainer({ pinnedToBottom = false }: Props) {
  const insets = useSafeAreaInsets();
  const [failed, setFailed] = useState(false);

  if (failed) return null;

  return (
    <View
      style={[styles.wrap, pinnedToBottom && { paddingBottom: Math.max(insets.bottom, spacing.sm) }]}
      pointerEvents="box-none">
      <BannerAd
        unitId={AdUnitIds.banner}
        size={BannerAdSize.ANCHORED_ADAPTIVE_BANNER}
        onAdFailedToLoad={() => setFailed(true)}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { width: '100%', alignItems: 'center', backgroundColor: 'transparent' },
});
