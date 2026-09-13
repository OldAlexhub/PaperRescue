import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, qualityColor } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { QualityReport } from '../types/document';

interface Props {
  report: QualityReport | null;
  analyzing?: boolean;
}

export function QualityScoreCard({ report, analyzing }: Props) {
  if (analyzing) {
    return (
      <View style={styles.card}>
        <Text style={[typography.caption]}>Checking scan quality…</Text>
      </View>
    );
  }
  if (!report) return null;

  const color = qualityColor(report.score);

  return (
    <View style={styles.card}>
      <View style={styles.headerRow}>
        <View style={[styles.scoreBadge, { borderColor: color }]}>
          <Text style={[styles.scoreText, { color }]}>{report.score}</Text>
        </View>
        <View style={styles.headerTextWrap}>
          <Text style={typography.bodyStrong}>Scan Quality: {report.score}/100</Text>
          <Text style={typography.caption}>An on-device estimate — not a scientific measurement</Text>
        </View>
      </View>

      {report.messages.map((msg, i) => (
        <Text key={`m-${i}`} style={[styles.line, { color: colors.success }]}>
          ✓ {msg}
        </Text>
      ))}
      {report.warnings.map((warn, i) => (
        <Text key={`w-${i}`} style={[styles.line, { color: colors.warning }]}>
          ⚠ {warn}
        </Text>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    padding: spacing.lg,
    borderWidth: 1,
    borderColor: colors.border,
  },
  headerRow: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.sm },
  scoreBadge: {
    width: 52,
    height: 52,
    borderRadius: 26,
    borderWidth: 3,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: spacing.md,
  },
  scoreText: { fontSize: 16, fontWeight: '800' },
  headerTextWrap: { flex: 1 },
  line: { fontSize: 13, marginTop: 4, fontWeight: '500' },
});
