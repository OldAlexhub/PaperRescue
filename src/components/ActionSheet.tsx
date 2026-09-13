import React from 'react';
import { Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { Icon, IconName } from './Icon';

export interface ActionSheetOption {
  key: string;
  label: string;
  icon: IconName;
  destructive?: boolean;
  onPress: () => void;
}

interface Props {
  visible: boolean;
  title?: string;
  options: ActionSheetOption[];
  onClose: () => void;
}

export function ActionSheet({ visible, title, options, onClose }: Props) {
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={styles.backdrop} onPress={onClose}>
        <View style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}>
          {title ? <Text style={styles.title}>{title}</Text> : null}
          {options.map(opt => (
            <Pressable
              key={opt.key}
              style={styles.row}
              onPress={() => {
                onClose();
                opt.onPress();
              }}>
              <Icon name={opt.icon} size={20} color={opt.destructive ? colors.danger : colors.textPrimary} />
              <Text style={[styles.rowLabel, opt.destructive && { color: colors.danger }]}>{opt.label}</Text>
            </Pressable>
          ))}
        </View>
      </Pressable>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: colors.overlay, justifyContent: 'flex-end' },
  sheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingTop: spacing.lg,
    paddingHorizontal: spacing.lg,
  },
  title: { fontSize: 13, fontWeight: '700', color: colors.textSecondary, marginBottom: spacing.sm },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: spacing.md },
  rowLabel: { marginLeft: spacing.md, fontSize: 16, fontWeight: '600', color: colors.textPrimary },
});
