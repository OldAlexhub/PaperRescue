import React, { useEffect, useState } from 'react';
import { Modal, StyleSheet, Text, TextInput, View } from 'react-native';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { PrimaryButton, SecondaryButton } from './Button';

interface Props {
  visible: boolean;
  initialValue: string;
  title?: string;
  onCancel: () => void;
  onConfirm: (value: string) => void;
}

export function RenameModal({ visible, initialValue, title = 'Rename document', onCancel, onConfirm }: Props) {
  const [value, setValue] = useState(initialValue);

  useEffect(() => {
    if (visible) setValue(initialValue);
  }, [visible, initialValue]);

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel}>
      <View style={styles.backdrop}>
        <View style={styles.card}>
          <Text style={typography.subtitle}>{title}</Text>
          <TextInput
            value={value}
            onChangeText={setValue}
            style={styles.input}
            autoFocus
            selectTextOnFocus
            maxLength={80}
            placeholder="Document name"
            placeholderTextColor={colors.textSecondary}
          />
          <View style={styles.row}>
            <View style={styles.buttonWrap}>
              <SecondaryButton label="Cancel" onPress={onCancel} />
            </View>
            <View style={[styles.buttonWrap, { marginLeft: spacing.sm }]}>
              <PrimaryButton label="Save" onPress={() => onConfirm(value.trim() || initialValue)} />
            </View>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: colors.overlay, alignItems: 'center', justifyContent: 'center', padding: spacing.xl },
  card: { width: '100%', backgroundColor: colors.surface, borderRadius: radius.lg, padding: spacing.lg },
  input: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    marginTop: spacing.md,
    marginBottom: spacing.lg,
    fontSize: 16,
    color: colors.textPrimary,
  },
  row: { flexDirection: 'row' },
  buttonWrap: { flex: 1 },
});
