/**
 * PaperRescue brand palette. Kept in one place so native (colors.xml) and JS
 * stay visually consistent — update both together if you change these.
 */
export const colors = {
  primary: '#1E3A5F',
  primaryDark: '#122740',
  rescue: '#FF7A29',
  rescueDark: '#D9600F',
  background: '#F5F7FA',
  surface: '#FFFFFF',
  surfaceAlt: '#EEF1F5',
  border: '#E3E7ED',
  textPrimary: '#101828',
  textSecondary: '#667085',
  textInverse: '#FFFFFF',
  success: '#12B76A',
  warning: '#F79009',
  danger: '#F04438',
  overlay: 'rgba(11,15,20,0.6)',
  scannerBackground: '#0B0F14',
} as const;

export function qualityColor(score: number): string {
  if (score >= 78) return colors.success;
  if (score >= 50) return colors.warning;
  return colors.danger;
}
