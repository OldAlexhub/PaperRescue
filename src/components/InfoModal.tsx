import React from 'react';
import { Modal, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { IconButton } from './Button';

interface Props {
  visible: boolean;
  title: string;
  body: string;
  onClose: () => void;
}

export function InfoModal({ visible, title, body, onClose }: Props) {
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      <View style={[styles.container, { paddingTop: insets.top }]}>
        <View style={styles.header}>
          <Text style={typography.title}>{title}</Text>
          <IconButton name="close" accessibilityLabel="Close" onPress={onClose} />
        </View>
        <ScrollView contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + spacing.xl }]}>
          <Text style={styles.body}>{body}</Text>
        </ScrollView>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: spacing.lg },
  content: { paddingHorizontal: spacing.lg },
  body: { fontSize: 14, lineHeight: 21, color: colors.textPrimary },
});
