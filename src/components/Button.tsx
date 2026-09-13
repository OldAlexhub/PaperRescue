import React from 'react';
import { ActivityIndicator, GestureResponderEvent, Pressable, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { Icon, IconName } from './Icon';

interface PrimaryProps {
  label: string;
  onPress: (e: GestureResponderEvent) => void;
  disabled?: boolean;
  loading?: boolean;
  variant?: 'primary' | 'rescue' | 'danger';
  icon?: IconName;
  fullWidth?: boolean;
}

export function PrimaryButton({ label, onPress, disabled, loading, variant = 'primary', icon, fullWidth = true }: PrimaryProps) {
  const bg = disabled ? colors.border : variant === 'rescue' ? colors.rescue : variant === 'danger' ? colors.danger : colors.primary;
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || loading}
      style={({ pressed }) => [
        styles.primary,
        { backgroundColor: bg, opacity: pressed ? 0.88 : 1 },
        fullWidth && { alignSelf: 'stretch' },
      ]}>
      {loading ? (
        <ActivityIndicator color={colors.textInverse} />
      ) : (
        <View style={styles.row}>
          {icon && <Icon name={icon} size={18} color={colors.textInverse} />}
          <Text style={[styles.primaryLabel, icon && { marginLeft: spacing.sm }]}>{label}</Text>
        </View>
      )}
    </Pressable>
  );
}

interface SecondaryProps {
  label: string;
  onPress: (e: GestureResponderEvent) => void;
  disabled?: boolean;
  loading?: boolean;
  icon?: IconName;
  fullWidth?: boolean;
  tone?: 'default' | 'danger';
}

export function SecondaryButton({ label, onPress, disabled, loading, icon, fullWidth = true, tone = 'default' }: SecondaryProps) {
  const color = tone === 'danger' ? colors.danger : colors.textPrimary;
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || loading}
      style={({ pressed }) => [
        styles.secondary,
        { opacity: pressed ? 0.7 : disabled ? 0.5 : 1, borderColor: tone === 'danger' ? colors.danger : colors.border },
        fullWidth && { alignSelf: 'stretch' },
      ]}>
      {loading ? (
        <ActivityIndicator color={color} />
      ) : (
        <View style={styles.row}>
          {icon && <Icon name={icon} size={18} color={color} />}
          <Text style={[styles.secondaryLabel, { color }, icon && { marginLeft: spacing.sm }]}>{label}</Text>
        </View>
      )}
    </Pressable>
  );
}

interface IconButtonProps {
  name: IconName;
  onPress: (e: GestureResponderEvent) => void;
  size?: number;
  color?: string;
  background?: string;
  accessibilityLabel: string;
}

export function IconButton({ name, onPress, size = 22, color = colors.textPrimary, background = 'transparent', accessibilityLabel }: IconButtonProps) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityLabel={accessibilityLabel}
      hitSlop={10}
      style={({ pressed }) => [
        styles.iconButton,
        { backgroundColor: background, opacity: pressed ? 0.6 : 1 },
      ]}>
      <Icon name={name} size={size} color={color} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center' },
  primary: {
    paddingVertical: spacing.md + 2,
    paddingHorizontal: spacing.xl,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryLabel: { color: colors.textInverse, fontSize: 16, fontWeight: '700' },
  secondary: {
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.xl,
    borderRadius: radius.md,
    borderWidth: 1.5,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surface,
  },
  secondaryLabel: { fontSize: 15, fontWeight: '700' },
  iconButton: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
