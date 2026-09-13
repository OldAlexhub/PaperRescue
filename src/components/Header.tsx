import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';
import { spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { IconButton } from './Button';

interface Props {
  title: string;
  onBack?: () => void;
  right?: React.ReactNode;
  subtitle?: string;
}

export function Header({ title, onBack, right, subtitle }: Props) {
  return (
    <View style={styles.container}>
      {onBack ? (
        <IconButton name="back" onPress={onBack} accessibilityLabel="Go back" size={26} />
      ) : (
        <View style={styles.spacer} />
      )}
      <View style={styles.titleWrap}>
        <Text style={typography.title} numberOfLines={1}>{title}</Text>
        {subtitle ? <Text style={typography.caption}>{subtitle}</Text> : null}
      </View>
      <View style={styles.right}>{right}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
  },
  spacer: { width: 40 },
  titleWrap: { flex: 1, marginLeft: spacing.sm },
  right: { minWidth: 40, alignItems: 'flex-end' },
});

export const HeaderStyles = { backgroundColor: colors.background };
