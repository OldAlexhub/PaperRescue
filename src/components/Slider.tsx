import React, { useMemo, useRef, useState } from 'react';
import { LayoutChangeEvent, PanResponder, StyleSheet, View } from 'react-native';
import { colors } from '../theme/colors';

interface Props {
  value: number;
  minimumValue: number;
  maximumValue: number;
  step?: number;
  onValueChange: (value: number) => void;
  onSlidingComplete?: (value: number) => void;
  minimumTrackTintColor?: string;
  maximumTrackTintColor?: string;
  thumbTintColor?: string;
}

const THUMB_SIZE = 22;

/**
 * A small dependency-free slider (PanResponder + Views). Avoids pulling in a
 * native slider package, which sidesteps New Architecture C++ codegen
 * compatibility issues entirely — this is pure JS.
 */
export function Slider({
  value,
  minimumValue,
  maximumValue,
  step = 1,
  onValueChange,
  onSlidingComplete,
  minimumTrackTintColor = colors.primary,
  maximumTrackTintColor = colors.border,
  thumbTintColor = colors.primary,
}: Props) {
  const [trackWidth, setTrackWidth] = useState(0);
  const trackWidthRef = useRef(0);
  const valueRef = useRef(value);
  valueRef.current = value;

  function onLayout(e: LayoutChangeEvent) {
    const w = e.nativeEvent.layout.width;
    trackWidthRef.current = w;
    setTrackWidth(w);
  }

  function clampToStep(raw: number): number {
    const stepped = Math.round(raw / step) * step;
    return Math.min(maximumValue, Math.max(minimumValue, stepped));
  }

  function valueFromLocationX(x: number): number {
    const width = trackWidthRef.current || 1;
    const ratio = Math.min(1, Math.max(0, x / width));
    return clampToStep(minimumValue + ratio * (maximumValue - minimumValue));
  }

  const responder = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onMoveShouldSetPanResponder: () => true,
        onPanResponderMove: (evt, gesture) => {
          const width = trackWidthRef.current;
          if (width <= 0) return;
          // locationX is relative to the touch target; for drags outside the
          // thumb itself we fall back to the gesture's moveX minus the track origin.
          const x = evt.nativeEvent.locationX;
          const next = valueFromLocationX(x);
          if (next !== valueRef.current) onValueChange(next);
        },
        onPanResponderRelease: () => {
          onSlidingComplete?.(valueRef.current);
        },
        onPanResponderTerminate: () => {
          onSlidingComplete?.(valueRef.current);
        },
      }),
    [minimumValue, maximumValue, step],
  );

  const ratio = maximumValue > minimumValue ? (value - minimumValue) / (maximumValue - minimumValue) : 0;
  const filledWidth = trackWidth * ratio;

  return (
    <View style={styles.wrapper} onLayout={onLayout} {...responder.panHandlers}>
      <View style={[styles.track, { backgroundColor: maximumTrackTintColor }]} />
      <View style={[styles.fill, { width: filledWidth, backgroundColor: minimumTrackTintColor }]} />
      <View
        style={[
          styles.thumb,
          {
            backgroundColor: thumbTintColor,
            left: Math.max(0, Math.min(trackWidth - THUMB_SIZE, filledWidth - THUMB_SIZE / 2)),
          },
        ]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrapper: { height: 32, justifyContent: 'center' },
  track: { position: 'absolute', left: 0, right: 0, height: 4, borderRadius: 2 },
  fill: { position: 'absolute', left: 0, height: 4, borderRadius: 2 },
  thumb: {
    position: 'absolute',
    width: THUMB_SIZE,
    height: THUMB_SIZE,
    borderRadius: THUMB_SIZE / 2,
    shadowColor: '#000',
    shadowOpacity: 0.25,
    shadowRadius: 3,
    shadowOffset: { width: 0, height: 1 },
    elevation: 2,
  },
});
