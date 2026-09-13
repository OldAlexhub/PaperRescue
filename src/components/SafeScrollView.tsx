import React from 'react';
import { ScrollView, ScrollViewProps, StyleSheet } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

interface Props extends ScrollViewProps {
  /** Extra bottom padding on top of the safe-area inset — use when a SafeBottomBar overlays content. */
  extraBottomPadding?: number;
}

/** A ScrollView whose content never hides behind the gesture/nav bar. */
export function SafeScrollView({ extraBottomPadding = 0, contentContainerStyle, children, ...rest }: Props) {
  const insets = useSafeAreaInsets();
  return (
    <ScrollView
      style={styles.flex}
      contentContainerStyle={[{ paddingBottom: insets.bottom + extraBottomPadding }, contentContainerStyle]}
      showsVerticalScrollIndicator={false}
      {...rest}>
      {children}
    </ScrollView>
  );
}

const styles = StyleSheet.create({ flex: { flex: 1 } });
