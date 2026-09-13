import React, { useEffect, useState } from 'react';
import { Alert, Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { ScreenContainer } from '../components/ScreenContainer';
import { SafeScrollView } from '../components/SafeScrollView';
import { SafeAdContainer } from '../components/SafeAdContainer';
import { Header } from '../components/Header';
import { FilterModeSelector } from '../components/FilterModeSelector';
import { InfoModal } from '../components/InfoModal';
import { Icon } from '../components/Icon';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { useSettingsStore } from '../state/settingsStore';
import { useDocumentStore } from '../state/documentStore';
import { PdfQuality } from '../types/document';
import * as repo from '../data/repository';
import { PRIVACY_POLICY_TEXT, ABOUT_TEXT, ADS_DISCLOSURE_TEXT } from '../legal/policyText';
const appVersion: string = require('../../package.json').version;

type Nav = NativeStackNavigationProp<RootStackParamList, 'Settings'>;

const PDF_QUALITY_OPTIONS: { value: PdfQuality; label: string }[] = [
  { value: 'original', label: 'Original' },
  { value: 'balanced', label: 'Balanced' },
  { value: 'smaller', label: 'Smaller' },
];

export function SettingsScreen() {
  const navigation = useNavigation<Nav>();
  const { settings, load, loaded, update } = useSettingsStore();
  const { refresh: refreshLibrary } = useDocumentStore();
  const [modal, setModal] = useState<'privacy' | 'about' | 'ads' | null>(null);
  const [clearing, setClearing] = useState(false);

  useEffect(() => {
    if (!loaded) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded]);

  function confirmClearData() {
    Alert.alert(
      'Clear local data',
      'This permanently deletes every scanned document, page, and thumbnail stored on this device. This cannot be undone.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Clear Everything',
          style: 'destructive',
          onPress: async () => {
            setClearing(true);
            try {
              await repo.clearAllData();
              await refreshLibrary();
              Alert.alert('Done', 'All local PaperRescue data has been cleared.');
            } finally {
              setClearing(false);
            }
          },
        },
      ],
    );
  }

  return (
    <ScreenContainer>
      <Header title="Settings" onBack={() => navigation.goBack()} />
      <SafeScrollView contentContainerStyle={styles.content}>
        <Text style={styles.sectionTitle}>Scanning</Text>
        <View style={styles.card}>
          <Text style={typography.bodyStrong}>Default scan filter</Text>
          <Text style={typography.caption}>Applied automatically to every new page</Text>
          <View style={{ marginTop: spacing.sm }}>
            <FilterModeSelector value={settings.defaultFilter} onChange={mode => update({ defaultFilter: mode })} />
          </View>
        </View>

        <View style={styles.card}>
          <View style={styles.row}>
            <View style={{ flex: 1 }}>
              <Text style={typography.bodyStrong}>Auto quality check</Text>
              <Text style={typography.caption}>Score every page right after capture</Text>
            </View>
            <Switch value={settings.autoQualityCheck} onValueChange={v => update({ autoQualityCheck: v })} trackColor={{ true: colors.primary }} />
          </View>
        </View>

        <View style={styles.card}>
          <View style={styles.row}>
            <View style={{ flex: 1 }}>
              <Text style={typography.bodyStrong}>Auto OCR</Text>
              <Text style={typography.caption}>Recognize text automatically after capture</Text>
            </View>
            <Switch value={settings.autoOcr} onValueChange={v => update({ autoOcr: v })} trackColor={{ true: colors.primary }} />
          </View>
        </View>

        <Text style={styles.sectionTitle}>Export</Text>
        <View style={styles.card}>
          <Text style={typography.bodyStrong}>Default PDF quality</Text>
          <View style={styles.pillRow}>
            {PDF_QUALITY_OPTIONS.map(opt => (
              <Pressable
                key={opt.value}
                onPress={() => update({ defaultPdfQuality: opt.value })}
                style={[styles.pill, settings.defaultPdfQuality === opt.value && styles.pillActive]}>
                <Text style={[styles.pillLabel, settings.defaultPdfQuality === opt.value && styles.pillLabelActive]}>{opt.label}</Text>
              </Pressable>
            ))}
          </View>
        </View>

        <Text style={styles.sectionTitle}>Privacy & About</Text>
        <SettingsLinkRow icon="text" label="Privacy Policy" onPress={() => setModal('privacy')} />
        <SettingsLinkRow icon="document" label="Ads Disclosure" onPress={() => setModal('ads')} />
        <SettingsLinkRow icon="folder" label="About PaperRescue" onPress={() => setModal('about')} />

        <Text style={styles.sectionTitle}>Storage</Text>
        <SettingsLinkRow icon="trash" label="Clear Local Data" destructive onPress={confirmClearData} loading={clearing} />

        <Text style={styles.version}>PaperRescue v{appVersion}</Text>

        <View style={styles.adSpacer}>
          <SafeAdContainer />
        </View>
      </SafeScrollView>

      <InfoModal visible={modal === 'privacy'} title="Privacy Policy" body={PRIVACY_POLICY_TEXT} onClose={() => setModal(null)} />
      <InfoModal visible={modal === 'ads'} title="Ads Disclosure" body={ADS_DISCLOSURE_TEXT} onClose={() => setModal(null)} />
      <InfoModal visible={modal === 'about'} title="About PaperRescue" body={ABOUT_TEXT} onClose={() => setModal(null)} />
    </ScreenContainer>
  );
}

function SettingsLinkRow({
  icon,
  label,
  onPress,
  destructive,
  loading,
}: {
  icon: React.ComponentProps<typeof Icon>['name'];
  label: string;
  onPress: () => void;
  destructive?: boolean;
  loading?: boolean;
}) {
  return (
    <Pressable style={styles.linkRow} onPress={onPress} disabled={loading}>
      <Icon name={icon} size={18} color={destructive ? colors.danger : colors.textSecondary} />
      <Text style={[styles.linkLabel, destructive && { color: colors.danger }]}>{loading ? 'Clearing…' : label}</Text>
      <Icon name="chevronRight" size={18} color={colors.textSecondary} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.lg },
  sectionTitle: { fontSize: 12, fontWeight: '800', color: colors.textSecondary, marginTop: spacing.lg, marginBottom: spacing.sm, textTransform: 'uppercase' },
  card: { backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.lg, borderWidth: 1, borderColor: colors.border, marginBottom: spacing.md },
  row: { flexDirection: 'row', alignItems: 'center' },
  pillRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  pill: { paddingVertical: spacing.sm, paddingHorizontal: spacing.md, borderRadius: radius.pill, backgroundColor: colors.surfaceAlt },
  pillActive: { backgroundColor: colors.primary },
  pillLabel: { fontSize: 13, fontWeight: '700', color: colors.textSecondary },
  pillLabelActive: { color: colors.textInverse },
  linkRow: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surface, borderRadius: radius.md,
    padding: spacing.lg, borderWidth: 1, borderColor: colors.border, marginBottom: spacing.sm,
  },
  linkLabel: { flex: 1, marginLeft: spacing.md, fontSize: 15, fontWeight: '600', color: colors.textPrimary },
  version: { textAlign: 'center', color: colors.textSecondary, fontSize: 12, marginTop: spacing.lg },
  adSpacer: { marginTop: spacing.lg },
});
