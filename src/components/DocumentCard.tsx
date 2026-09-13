import React from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { Icon } from './Icon';
import { formatDate, pageCountLabel } from '../utils/format';
import { Document, Page } from '../types/document';

interface Props {
  document: Document;
  firstPage: Page | undefined;
  fileSizeBytes: number;
  onPress: () => void;
  onMore: () => void;
}

export function DocumentCard({ document, firstPage, fileSizeBytes, onPress, onMore }: Props) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.card, pressed && { opacity: 0.85 }]}>
      <View style={styles.thumbWrap}>
        {firstPage ? (
          <Image source={{ uri: `file://${firstPage.thumbnailPath}` }} style={styles.thumb} resizeMode="cover" />
        ) : (
          <Icon name="document" size={26} color={colors.textSecondary} />
        )}
      </View>
      <View style={styles.info}>
        <Text style={typography.bodyStrong} numberOfLines={1}>{document.name}</Text>
        <Text style={typography.caption}>
          {pageCountLabel(document.pageIds.length)} · {formatDate(document.updatedAt)}
          {fileSizeBytes > 0 ? ` · ${Math.max(1, Math.round(fileSizeBytes / 1024))} KB` : ''}
        </Text>
      </View>
      <Pressable onPress={onMore} hitSlop={12} style={styles.moreButton}>
        <Icon name="more" size={18} color={colors.textSecondary} />
      </Pressable>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.md,
    borderWidth: 1,
    borderColor: colors.border,
  },
  thumbWrap: {
    width: 56,
    height: 72,
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceAlt,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
    marginRight: spacing.md,
  },
  thumb: { width: '100%', height: '100%' },
  info: { flex: 1 },
  moreButton: { padding: spacing.xs },
});
