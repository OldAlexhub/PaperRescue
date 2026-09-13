import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { FilterMode } from '../types/document';

const OPTIONS: { value: FilterMode; label: string }[] = [
  { value: 'original', label: 'Original' },
  { value: 'enhanced', label: 'Enhanced' },
  { value: 'grayscale', label: 'Grayscale' },
  { value: 'bw', label: 'B & W' },
];

interface Props {
  value: FilterMode;
  onChange: (mode: FilterMode) => void;
}

export function FilterModeSelector({ value, onChange }: Props) {
  return (
    <View style={styles.row}>
      {OPTIONS.map(opt => {
        const selected = opt.value === value;
        return (
          <Pressable
            key={opt.value}
            onPress={() => onChange(opt.value)}
            style={[styles.pill, selected && styles.pillSelected]}>
            <Text style={[styles.label, selected && styles.labelSelected]}>{opt.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  pill: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: colors.surfaceAlt,
  },
  pillSelected: { backgroundColor: colors.primary },
  label: { fontSize: 13, fontWeight: '700', color: colors.textSecondary },
  labelSelected: { color: colors.textInverse },
});
