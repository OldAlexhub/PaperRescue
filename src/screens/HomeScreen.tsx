import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenContainer } from '../components/ScreenContainer';
import { SafeScrollView } from '../components/SafeScrollView';
import { SafeAdContainer } from '../components/SafeAdContainer';
import { EmptyState } from '../components/EmptyState';
import { DocumentCard } from '../components/DocumentCard';
import { LoadingOverlay } from '../components/LoadingOverlay';
import { Icon } from '../components/Icon';
import { colors } from '../theme/colors';
import { spacing, radius } from '../theme/spacing';
import { typography } from '../theme/typography';
import { useDocumentStore } from '../state/documentStore';
import { useSettingsStore } from '../state/settingsStore';
import { RootStackParamList } from '../navigation/types';
import * as repo from '../data/repository';
import { Gallery } from '../native/Gallery';
import { ingestImportedImage } from '../logic/pageIngest';
import { friendlyErrorMessage } from '../utils/errors';
import { pageCountLabel } from '../utils/format';

type Nav = NativeStackNavigationProp<RootStackParamList>;

function defaultDocumentName(): string {
  const now = new Date();
  return `Scan ${now.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} ${now.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}`;
}

export function HomeScreen() {
  const navigation = useNavigation<Nav>();
  const { items, refresh } = useDocumentStore();
  const { settings, load: loadSettings, loaded: settingsLoaded } = useSettingsStore();
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    if (!settingsLoaded) loadSettings();
    // Settings only need loading once per app session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );

  async function startScan(mode: 'single' | 'rescue') {
    try {
      const doc = await repo.createDocument(defaultDocumentName());
      navigation.navigate('Scanner', { docId: doc.id, pageNumber: 1, mode });
    } catch (e) {
      Alert.alert('Could not start scan', friendlyErrorMessage(e));
    }
  }

  async function importPhotos() {
    try {
      const picked = await Gallery.pickImages(50);
      if (!picked || picked.length === 0) return;
      setBusy('Importing photos…');
      const doc = await repo.createDocument(defaultDocumentName());
      for (const path of picked) {
        await ingestImportedImage(doc.id, path, settings);
      }
      setBusy(null);
      await refresh();
      navigation.navigate('DocumentEditor', { docId: doc.id });
    } catch (e) {
      setBusy(null);
      Alert.alert('Import failed', friendlyErrorMessage(e));
    }
  }

  const recent = items.slice(0, 5);

  return (
    <ScreenContainer>
      <SafeScrollView contentContainerStyle={styles.content}>
        <View style={styles.brandRow}>
          <View>
            <Text style={typography.display}>PaperRescue</Text>
            <Text style={typography.caption}>Scan it. Rescue it. PDF it.</Text>
          </View>
          <Pressable
            onPress={() => navigation.navigate('Settings')}
            hitSlop={10}
            style={styles.settingsButton}>
            <Icon name="settings" size={20} color={colors.textSecondary} />
          </Pressable>
        </View>

        <Pressable style={styles.searchBar} onPress={() => navigation.navigate('Library')}>
          <Icon name="search" size={16} color={colors.textSecondary} />
          <Text style={styles.searchPlaceholder}>Search documents…</Text>
        </Pressable>

        <View style={styles.actionsGrid}>
          <Pressable style={[styles.actionCard, styles.actionPrimary]} onPress={() => startScan('single')}>
            <Icon name="camera" size={28} color={colors.textInverse} />
            <Text style={styles.actionTitle}>Scan Document</Text>
            <Text style={styles.actionSubtitle}>Auto edge detection & crop</Text>
          </Pressable>

          <Pressable style={[styles.actionCard, styles.actionRescue]} onPress={() => startScan('rescue')}>
            <Icon name="rescue" size={28} color={colors.textInverse} />
            <Text style={styles.actionTitle}>Rescue Scan</Text>
            <Text style={styles.actionSubtitle}>Multi-frame quality boost</Text>
          </Pressable>
        </View>

        <Pressable style={styles.importRow} onPress={importPhotos}>
          <Icon name="gallery" size={20} color={colors.primary} />
          <Text style={styles.importLabel}>Import Photos from Gallery</Text>
        </Pressable>

        <View style={styles.sectionHeaderRow}>
          <Text style={typography.subtitle}>Recent documents</Text>
          <Pressable onPress={() => navigation.navigate('Library')}>
            <Text style={styles.libraryLink}>View Library ({items.length})</Text>
          </Pressable>
        </View>

        {recent.length === 0 ? (
          <EmptyState
            icon="document"
            title="No documents yet"
            message="Scan your first document or import photos to get started."
          />
        ) : (
          recent.map(({ document, firstPage }) => (
            <DocumentCard
              key={document.id}
              document={document}
              firstPage={firstPage}
              fileSizeBytes={0}
              onPress={() => navigation.navigate('DocumentEditor', { docId: document.id })}
              onMore={() => navigation.navigate('DocumentEditor', { docId: document.id })}
            />
          ))
        )}

        <Text style={styles.countLine}>{pageCountLabel(items.length).replace('page', 'document')} in your library</Text>

        <View style={styles.adSpacer}>
          <SafeAdContainer />
        </View>
      </SafeScrollView>

      <LoadingOverlay visible={busy !== null} label={busy ?? undefined} />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.lg },
  brandRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: spacing.lg },
  settingsButton: {
    width: 40, height: 40, borderRadius: 20, backgroundColor: colors.surface,
    alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: colors.border,
  },
  searchBar: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surface,
    borderRadius: radius.pill, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: spacing.lg, paddingVertical: spacing.md, marginBottom: spacing.lg,
  },
  searchPlaceholder: { marginLeft: spacing.sm, color: colors.textSecondary, fontSize: 15 },
  actionsGrid: { flexDirection: 'row', gap: spacing.md, marginBottom: spacing.md },
  actionCard: { flex: 1, borderRadius: radius.lg, padding: spacing.lg, minHeight: 130, justifyContent: 'space-between' },
  actionPrimary: { backgroundColor: colors.primary },
  actionRescue: { backgroundColor: colors.rescue },
  actionTitle: { color: colors.textInverse, fontSize: 16, fontWeight: '800', marginTop: spacing.md },
  actionSubtitle: { color: 'rgba(255,255,255,0.85)', fontSize: 12, marginTop: 2 },
  importRow: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surface,
    borderRadius: radius.md, padding: spacing.md, borderWidth: 1, borderColor: colors.border, marginBottom: spacing.xl,
  },
  importLabel: { marginLeft: spacing.sm, fontWeight: '700', color: colors.primary },
  sectionHeaderRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.md },
  libraryLink: { color: colors.primary, fontWeight: '700', fontSize: 13 },
  countLine: { textAlign: 'center', color: colors.textSecondary, fontSize: 12, marginTop: spacing.sm, marginBottom: spacing.lg },
  adSpacer: { marginTop: spacing.sm },
});
