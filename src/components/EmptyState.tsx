import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';
import { spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { Icon, IconName } from './Icon';

interface Props {
  icon: IconName;
  title: string;
  message?: string;
  children?: React.ReactNode;
}

export function EmptyState({ icon, title, message, children }: Props) {
  return (
    <View style={styles.container}>
      <View style={styles.iconWrap}>
        <Icon name={icon} size={36} color={colors.textSecondary} />
      </View>
      <Text style={[typography.subtitle, styles.title]}>{title}</Text>
      {message ? <Text style={[typography.body, styles.message]}>{message}</Text> : null}
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.xxl },
  iconWrap: {
    width: 76,
    height: 76,
    borderRadius: 38,
    backgroundColor: colors.surfaceAlt,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.lg,
  },
  title: { textAlign: 'center', marginBottom: spacing.xs },
  message: { textAlign: 'center', color: colors.textSecondary, marginBottom: spacing.lg },
});
