import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Slider } from './Slider';
import { colors } from '../theme/colors';
import { spacing } from '../theme/spacing';

interface Props {
  label: string;
  value: number;
  minimumValue: number;
  maximumValue: number;
  onValueChange: (value: number) => void;
  onSlidingComplete?: (value: number) => void;
}

export function SliderControl({ label, value, minimumValue, maximumValue, onValueChange, onSlidingComplete }: Props) {
  return (
    <View style={styles.container}>
      <View style={styles.labelRow}>
        <Text style={styles.label}>{label}</Text>
        <Text style={styles.value}>{Math.round(value)}</Text>
      </View>
      <Slider
        value={value}
        minimumValue={minimumValue}
        maximumValue={maximumValue}
        step={1}
        onValueChange={onValueChange}
        onSlidingComplete={onSlidingComplete}
        minimumTrackTintColor={colors.primary}
        maximumTrackTintColor={colors.border}
        thumbTintColor={colors.primary}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { marginBottom: spacing.md },
  labelRow: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 2 },
  label: { fontSize: 13, fontWeight: '600', color: colors.textPrimary },
  value: { fontSize: 13, fontWeight: '600', color: colors.textSecondary },
});
