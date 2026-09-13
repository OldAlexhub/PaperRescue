import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';

export type IconName =
  | 'back'
  | 'close'
  | 'chevronRight'
  | 'chevronUp'
  | 'chevronDown'
  | 'more'
  | 'search'
  | 'plus'
  | 'check'
  | 'trash'
  | 'share'
  | 'edit'
  | 'duplicate'
  | 'rotateLeft'
  | 'rotateRight'
  | 'crop'
  | 'sort'
  | 'settings'
  | 'document'
  | 'camera'
  | 'rescue'
  | 'text'
  | 'folder'
  | 'gallery'
  | 'pdf';

interface Props {
  name: IconName;
  size?: number;
  color?: string;
}

const GLYPHS: Partial<Record<IconName, string>> = {
  back: '‹',
  close: '✕',
  chevronRight: '›',
  chevronUp: '⌃',
  chevronDown: '⌄',
  check: '✓',
  rotateLeft: '↺',
  rotateRight: '↻',
  sort: '⇅',
};

/**
 * A small, dependency-free icon set. Simple glyphs use system Unicode
 * characters; anything without a clean monochrome glyph is drawn from basic
 * shapes so it renders identically across devices without an icon font/SVG
 * library.
 */
export function Icon({ name, size = 22, color = colors.textPrimary }: Props) {
  const glyph = GLYPHS[name];
  if (glyph) {
    return (
      <Text style={{ fontSize: size, color, fontWeight: '600', includeFontPadding: false }}>
        {glyph}
      </Text>
    );
  }

  const box = { width: size, height: size };

  switch (name) {
    case 'more':
      return (
        <View style={[styles.center, box]}>
          {[0, 1, 2].map(i => (
            <View
              key={i}
              style={{
                width: size * 0.14,
                height: size * 0.14,
                borderRadius: size * 0.07,
                backgroundColor: color,
                marginVertical: size * 0.06,
              }}
            />
          ))}
        </View>
      );
    case 'plus':
      return (
        <View style={[styles.center, box]}>
          <View style={{ position: 'absolute', width: size * 0.72, height: size * 0.12, backgroundColor: color, borderRadius: 2 }} />
          <View style={{ position: 'absolute', width: size * 0.12, height: size * 0.72, backgroundColor: color, borderRadius: 2 }} />
        </View>
      );
    case 'search':
      return (
        <View style={[styles.center, box]}>
          <View
            style={{
              width: size * 0.62,
              height: size * 0.62,
              borderRadius: size * 0.31,
              borderWidth: size * 0.11,
              borderColor: color,
              position: 'absolute',
              top: size * 0.06,
              left: size * 0.06,
            }}
          />
          <View
            style={{
              width: size * 0.34,
              height: size * 0.11,
              backgroundColor: color,
              borderRadius: 2,
              position: 'absolute',
              bottom: size * 0.08,
              right: size * 0.04,
              transform: [{ rotate: '45deg' }],
            }}
          />
        </View>
      );
    case 'trash':
      return (
        <View style={[styles.center, box]}>
          <View style={{ width: size * 0.16, height: size * 0.1, backgroundColor: color, borderRadius: 1, marginBottom: -1 }} />
          <View
            style={{
              width: size * 0.6,
              height: size * 0.56,
              borderWidth: size * 0.09,
              borderColor: color,
              borderTopWidth: 0,
              borderBottomLeftRadius: 3,
              borderBottomRightRadius: 3,
            }}
          />
          <View style={{ position: 'absolute', top: size * 0.28, width: size * 0.66, height: size * 0.09, backgroundColor: color }} />
        </View>
      );
    case 'share':
      return (
        <View style={[styles.center, box]}>
          <View style={{ width: size * 0.1, height: size * 0.5, backgroundColor: color, position: 'absolute', bottom: size * 0.08 }} />
          <View
            style={{
              width: 0,
              height: 0,
              borderLeftWidth: size * 0.2,
              borderRightWidth: size * 0.2,
              borderBottomWidth: size * 0.28,
              borderLeftColor: 'transparent',
              borderRightColor: 'transparent',
              borderBottomColor: color,
              position: 'absolute',
              top: size * 0.06,
            }}
          />
        </View>
      );
    case 'edit':
      return (
        <View style={[styles.center, box, { transform: [{ rotate: '45deg' }] }]}>
          <View style={{ width: size * 0.16, height: size * 0.62, backgroundColor: color, borderRadius: 2 }} />
          <View
            style={{
              width: 0,
              height: 0,
              borderLeftWidth: size * 0.08,
              borderRightWidth: size * 0.08,
              borderTopWidth: size * 0.14,
              borderLeftColor: 'transparent',
              borderRightColor: 'transparent',
              borderTopColor: color,
              position: 'absolute',
              bottom: -size * 0.12,
            }}
          />
        </View>
      );
    case 'duplicate':
      return (
        <View style={[styles.center, box]}>
          <View
            style={{
              width: size * 0.52,
              height: size * 0.52,
              borderWidth: size * 0.08,
              borderColor: color,
              borderRadius: 3,
              position: 'absolute',
              top: 0,
              right: 0,
            }}
          />
          <View
            style={{
              width: size * 0.52,
              height: size * 0.52,
              borderRadius: 3,
              backgroundColor: colors.surface,
              borderWidth: size * 0.08,
              borderColor: color,
              position: 'absolute',
              bottom: 0,
              left: 0,
            }}
          />
        </View>
      );
    case 'crop':
      return (
        <View style={[styles.center, box]}>
          <View style={{ width: size * 0.6, height: size * 0.6, borderWidth: size * 0.09, borderColor: color }} />
        </View>
      );
    case 'settings':
      return (
        <View style={[styles.center, box]}>
          {[0.3, 0.55, 0.8].map((w, i) => (
            <View
              key={i}
              style={{
                width: size * w,
                height: size * 0.1,
                backgroundColor: color,
                borderRadius: 2,
                marginVertical: size * 0.05,
              }}
            />
          ))}
        </View>
      );
    case 'text':
      return (
        <View style={[styles.center, box]}>
          {[0.7, 0.5, 0.62].map((w, i) => (
            <View
              key={i}
              style={{
                width: size * w,
                height: size * 0.1,
                backgroundColor: color,
                borderRadius: 2,
                marginVertical: size * 0.05,
                alignSelf: 'flex-start',
              }}
            />
          ))}
        </View>
      );
    case 'document':
      return (
        <View style={[styles.center, box]}>
          <View
            style={{
              width: size * 0.56,
              height: size * 0.72,
              borderWidth: size * 0.08,
              borderColor: color,
              borderRadius: 3,
            }}
          />
        </View>
      );
    case 'pdf':
      return (
        <View style={[styles.center, box, { backgroundColor: colors.danger, borderRadius: 4 }]}>
          <Text style={{ fontSize: size * 0.36, color: colors.textInverse, fontWeight: '800' }}>PDF</Text>
        </View>
      );
    case 'folder':
      return (
        <View style={[styles.center, box]}>
          <View
            style={{
              width: size * 0.7,
              height: size * 0.14,
              backgroundColor: color,
              borderTopLeftRadius: 2,
              borderTopRightRadius: 2,
              alignSelf: 'flex-start',
              marginLeft: size * 0.06,
            }}
          />
          <View style={{ width: size * 0.82, height: size * 0.5, backgroundColor: color, borderRadius: 3 }} />
        </View>
      );
    case 'gallery':
      return (
        <View style={[styles.center, box, { borderWidth: size * 0.07, borderColor: color, borderRadius: 4 }]}>
          <View
            style={{
              width: size * 0.2,
              height: size * 0.2,
              borderRadius: size * 0.1,
              backgroundColor: color,
              position: 'absolute',
              top: size * 0.12,
              left: size * 0.14,
            }}
          />
          <View
            style={{
              width: 0,
              height: 0,
              borderLeftWidth: size * 0.22,
              borderRightWidth: size * 0.22,
              borderBottomWidth: size * 0.26,
              borderLeftColor: 'transparent',
              borderRightColor: 'transparent',
              borderBottomColor: color,
              position: 'absolute',
              bottom: size * 0.06,
            }}
          />
        </View>
      );
    case 'camera':
      return (
        <View style={[styles.center, box, { borderWidth: size * 0.07, borderColor: color, borderRadius: 5 }]}>
          <View style={{ width: size * 0.3, height: size * 0.3, borderRadius: size * 0.15, borderWidth: size * 0.06, borderColor: color }} />
        </View>
      );
    case 'rescue':
      return (
        <View style={[styles.center, box, { backgroundColor: colors.rescue, borderRadius: size / 2 }]}>
          <Text style={{ fontSize: size * 0.5, color: colors.textInverse, fontWeight: '800' }}>R</Text>
        </View>
      );
    default:
      return <View style={box} />;
  }
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', justifyContent: 'center' },
});
