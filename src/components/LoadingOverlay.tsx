import React from 'react';
import { ActivityIndicator, Modal, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';
import { spacing, radius } from '../theme/spacing';

interface Props {
  visible: boolean;
  label?: string;
}

/** Full-screen progress indicator for operations that take noticeable time (Rescue Scan, PDF export, batch OCR). */
export function LoadingOverlay({ visible, label = 'Working…' }: Props) {
  if (!visible) return null;
  return (
    <Modal transparent visible={visible} animationType="fade">
      <View style={styles.backdrop}>
        <View style={styles.card}>
          <ActivityIndicator size="large" color={colors.rescue} />
          <Text style={styles.label}>{label}</Text>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: colors.overlay,
    alignItems: 'center',
    justifyContent: 'center',
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    paddingVertical: spacing.xl,
    paddingHorizontal: spacing.xxl,
    alignItems: 'center',
    minWidth: 220,
  },
  label: { marginTop: spacing.md, color: colors.textPrimary, fontWeight: '600', textAlign: 'center' },
});
