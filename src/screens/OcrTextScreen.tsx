import React, { useMemo, useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import Clipboard from '@react-native-clipboard/clipboard';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { ScreenContainer } from '../components/ScreenContainer';
import { SafeAdContainer } from '../components/SafeAdContainer';
import { Header } from '../components/Header';
import { EmptyState } from '../components/EmptyState';
import { IconButton, PrimaryButton, SecondaryButton } from '../components/Button';
import { Icon } from '../components/Icon';
import { LoadingOverlay } from '../components/LoadingOverlay';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { useDocument } from '../hooks/useDocument';
import { Ocr } from '../native/Ocr';
import * as repo from '../data/repository';
import { Share } from '../native/Share';
import { getAppDirectories } from '../data/appDirs';
import { FileSystem } from '../native/FileSystem';
import { sanitizeFileName } from '../utils/format';
import { friendlyErrorMessage } from '../utils/errors';

type Nav = NativeStackNavigationProp<RootStackParamList, 'OcrText'>;
type Rt = { params: RootStackParamList['OcrText'] };

export function OcrTextScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute() as Rt;
  const { docId } = route.params;
  const { document, refresh } = useDocument(docId);
  const [pageIndex, setPageIndex] = useState(0);
  const [query, setQuery] = useState('');
  const [runningOcrFor, setRunningOcrFor] = useState<string | null>(null);

  if (!document) {
    return (
      <ScreenContainer>
        <Header title="OCR / Text" onBack={() => navigation.goBack()} />
      </ScreenContainer>
    );
  }

  const page = document.pages[pageIndex];
  const anyOcr = document.pages.some(p => p.ocr);

  const highlightedParagraphs = useMemo(() => {
    const text = page?.ocr?.text ?? '';
    if (!query.trim()) return [{ text, match: false }];
    const q = query.trim().toLowerCase();
    const parts: { text: string; match: boolean }[] = [];
    let rest = text;
    let lowerRest = text.toLowerCase();
    while (rest.length > 0) {
      const idx = lowerRest.indexOf(q);
      if (idx === -1) {
        parts.push({ text: rest, match: false });
        break;
      }
      if (idx > 0) parts.push({ text: rest.slice(0, idx), match: false });
      parts.push({ text: rest.slice(idx, idx + q.length), match: true });
      rest = rest.slice(idx + q.length);
      lowerRest = lowerRest.slice(idx + q.length);
    }
    return parts;
  }, [page?.ocr?.text, query]);

  async function runOcrForCurrentPage() {
    if (!page) return;
    setRunningOcrFor(page.id);
    try {
      const result = await Ocr.recognize(page.processedImagePath);
      await repo.updatePage(page.id, { ocr: { ...result, computedAt: Date.now() } });
      await refresh();
    } catch (e) {
      Alert.alert('Text recognition failed', friendlyErrorMessage(e));
    } finally {
      setRunningOcrFor(null);
    }
  }

  function copyAll() {
    const text = page?.ocr?.text ?? '';
    if (!text) return;
    Clipboard.setString(text);
  }

  async function shareText() {
    const text = page?.ocr?.text;
    if (!text || !page) return;
    try {
      const dirs = await getAppDirectories();
      const path = `${dirs.exportsDir}/${sanitizeFileName(document.name)}_page${pageIndex + 1}.txt`;
      await FileSystem.writeTextFile(path, text);
      await Share.shareFile(path, 'text/plain', document.name);
    } catch (e) {
      Alert.alert('Share failed', friendlyErrorMessage(e));
    }
  }

  return (
    <ScreenContainer>
      <Header
        title="OCR / Text"
        subtitle={document.name}
        onBack={() => navigation.goBack()}
        right={<IconButton name="share" accessibilityLabel="Share text" onPress={shareText} />}
      />

      {!anyOcr ? (
        <EmptyState icon="text" title="No text recognized yet" message="Run text recognition on a page to see results here.">
          <PrimaryButton label="Run Text Recognition" onPress={runOcrForCurrentPage} loading={runningOcrFor === page?.id} />
        </EmptyState>
      ) : (
        <>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.pageSelector} contentContainerStyle={styles.pageSelectorContent}>
            {document.pages.map((p, i) => (
              <Pressable
                key={p.id}
                onPress={() => setPageIndex(i)}
                style={[styles.pagePill, i === pageIndex && styles.pagePillActive]}>
                <Text style={[styles.pagePillLabel, i === pageIndex && styles.pagePillLabelActive]}>
                  Page {i + 1}{!p.ocr ? ' •' : ''}
                </Text>
              </Pressable>
            ))}
          </ScrollView>

          <View style={styles.searchBar}>
            <Icon name="search" size={16} color={colors.textSecondary} />
            <TextInput
              value={query}
              onChangeText={setQuery}
              placeholder="Search recognized text…"
              placeholderTextColor={colors.textSecondary}
              style={styles.searchInput}
            />
          </View>

          <ScrollView style={styles.textArea} contentContainerStyle={{ padding: spacing.lg }}>
            {!page?.ocr ? (
              <View style={{ alignItems: 'flex-start' }}>
                <Text style={typography.caption}>No text recognized on this page yet.</Text>
                <View style={{ height: spacing.md }} />
                <SecondaryButton label="Run Text Recognition" onPress={runOcrForCurrentPage} loading={runningOcrFor === page?.id} fullWidth={false} />
              </View>
            ) : page.ocr.text.trim().length === 0 ? (
              <Text style={typography.caption}>No text was found on this page.</Text>
            ) : (
              <Text style={styles.bodyText} selectable>
                {highlightedParagraphs.map((p, i) =>
                  p.match ? (
                    <Text key={i} style={styles.highlight}>{p.text}</Text>
                  ) : (
                    <Text key={i}>{p.text}</Text>
                  ),
                )}
              </Text>
            )}
          </ScrollView>

          <View style={styles.footer}>
            <SecondaryButton label="Copy All Text" icon="duplicate" onPress={copyAll} disabled={!page?.ocr?.text} />
          </View>
          <SafeAdContainer pinnedToBottom />
        </>
      )}
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  pageSelector: { maxHeight: 48, paddingHorizontal: spacing.lg, marginBottom: spacing.sm },
  pageSelectorContent: { gap: spacing.sm, alignItems: 'center' },
  pagePill: { paddingHorizontal: spacing.md, paddingVertical: 6, borderRadius: radius.pill, backgroundColor: colors.surfaceAlt },
  pagePillActive: { backgroundColor: colors.primary },
  pagePillLabel: { fontSize: 12, fontWeight: '700', color: colors.textSecondary },
  pagePillLabelActive: { color: colors.textInverse },
  searchBar: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surface,
    borderRadius: radius.pill, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: spacing.lg, marginHorizontal: spacing.lg, marginBottom: spacing.sm,
  },
  searchInput: { flex: 1, marginLeft: spacing.sm, paddingVertical: spacing.sm, fontSize: 14, color: colors.textPrimary },
  textArea: { flex: 1, marginHorizontal: spacing.lg, backgroundColor: colors.surface, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border },
  bodyText: { fontSize: 15, lineHeight: 22, color: colors.textPrimary },
  highlight: { backgroundColor: '#FFE59A' },
  footer: { padding: spacing.lg },
});
