import { TextStyle } from 'react-native';
import { colors } from './colors';

export const typography: Record<string, TextStyle> = {
  display: { fontSize: 28, fontWeight: '800', color: colors.textPrimary, letterSpacing: -0.3 },
  title: { fontSize: 22, fontWeight: '700', color: colors.textPrimary },
  subtitle: { fontSize: 17, fontWeight: '600', color: colors.textPrimary },
  body: { fontSize: 15, fontWeight: '400', color: colors.textPrimary },
  bodyStrong: { fontSize: 15, fontWeight: '600', color: colors.textPrimary },
  caption: { fontSize: 13, fontWeight: '400', color: colors.textSecondary },
  captionStrong: { fontSize: 13, fontWeight: '700', color: colors.textSecondary },
  button: { fontSize: 16, fontWeight: '700', color: colors.textInverse },
};
