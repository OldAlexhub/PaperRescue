import React from 'react';
import { StyleSheet, ViewProps } from 'react-native';
import { SafeAreaView, Edge } from 'react-native-safe-area-context';
import { colors } from '../theme/colors';

interface Props extends ViewProps {
  edges?: Edge[];
  background?: string;
  children: React.ReactNode;
}

/** Base full-screen wrapper: respects the status bar, gesture area and 3-button nav bar on every device. */
export function ScreenContainer({ edges = ['top', 'left', 'right'], background = colors.background, style, children, ...rest }: Props) {
  return (
    <SafeAreaView edges={edges} style={[styles.container, { backgroundColor: background }, style]} {...rest}>
      {children}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
});
