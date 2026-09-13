import React from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, ViewProps } from 'react-native';

interface Props extends ViewProps {
  children: React.ReactNode;
}

/** Wrap screens with text inputs (rename dialogs, search) so the keyboard never covers them. */
export function KeyboardSafeScreen({ style, children, ...rest }: Props) {
  return (
    <KeyboardAvoidingView
      style={[styles.flex, style]}
      behavior={Platform.OS === 'android' ? 'height' : 'padding'}
      {...rest}>
      {children}
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({ flex: { flex: 1 } });
