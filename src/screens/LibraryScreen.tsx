import React, { useCallback, useMemo, useState } from 'react';
import { Alert, Image, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { ScreenContainer } from '../components/ScreenContainer';
import { SafeScrollView } from '../components/SafeScrollView';
import { SafeAdContainer } from '../components/SafeAdContainer';
import { Header } from '../components/Header';
import { EmptyState } from '../components/EmptyState';
import { Icon } from '../components/Icon';
import { ActionSheet } from '../components/ActionSheet';
import { RenameModal } from '../components/RenameModal';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { useDocumentStore } from '../state/documentStore';
import { Document, Page } from '../types/document';
import { Share } from '../native/Share';
import { Pdf } from '../native/Pdf';
import { getAppDirectories } from '../data/appDirs';
import { exportedPdfPath } from '../data/paths';
import { sanitizeFileName, formatDate, formatFileSize, pageCountLabel } from '../utils/format';
import { useDocumentSize } from '../hooks/useDocumentSize';
import { friendlyErrorMessage } from '../utils/errors';
import * as repo from '../data/repository';

type Nav = NativeStackNavigationProp<RootStackParamList, 'Library'>;
type SortMode = 'recent' | 'name' | 'pages';

function LibraryRow({
  document,
  firstPage,
  onPress,
  onMore,
}: {
  document: Document;
  firstPage: Page | undefined;
  onPress: () => void;
  onMore: () => void;
}) {
  const sizeBytes = useDocumentSize(document.id);
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.card, pressed && { opacity: 0.85 }]}>
      <View style={styles.thumbWrap}>
        {firstPage ? (
          <Image source={{ uri: `file://${firstPage.thumbnailPath}` }} style={styles.thumbImage} resizeMode="cover" />
        ) : (
          <Icon name="document" size={24} color={colors.textSecondary} />
        )}
      </View>
      <View style={styles.info}>
        <Text style={styles.name} numberOfLines={1}>{document.name}</Text>
        <Text style={styles.meta}>
          {pageCountLabel(document.pageIds.length)} · {formatDate(document.updatedAt)} · {formatFileSize(sizeBytes)}
        </Text>
      </View>
      <Pressable onPress={onMore} hitSlop={12} style={styles.moreButton}>
        <Icon name="more" size={18} color={colors.textSecondary} />
      </Pressable>
    </Pressable>
  );
}

export function LibraryScreen() {
  const navigation = useNavigation<Nav>();
  const { items, refresh, rename, remove, duplicate } = useDocumentStore();
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<SortMode>('recent');
  const [sheetDocId, setSheetDocId] = useState<string | null>(null);
  const [renameDocId, setRenameDocId] = useState<string | null>(null);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );

  const filtered = useMemo(() => {
    let list = items;
    if (query.trim()) {
      const q = query.trim().toLowerCase();
      list = list.filter(i => i.document.name.toLowerCase().includes(q));
    }
    const sorted = [...list];
    if (sort === 'name') sorted.sort((a, b) => a.document.name.localeCompare(b.document.name));
    else if (sort === 'pages') sorted.sort((a, b) => b.document.pageIds.length - a.document.pageIds.length);
    else sorted.sort((a, b) => b.document.updatedAt - a.document.updatedAt);
    return sorted;
  }, [items, query, sort]);

  const activeDoc = items.find(i => i.document.id === sheetDocId)?.document ?? null;
  const renameDoc = items.find(i => i.document.id === renameDocId)?.document ?? null;

  async function handleShareDocument(doc: Document) {
    try {
      const full = await repo.getDocumentWithPages(doc.id);
      if (!full || full.pages.length === 0) return;
      const dirs = await getAppDirectories();
      const safeName = sanitizeFileName(doc.name);
      const hasOcr = full.pages.some(p => p.ocr);
      const pages = full.pages.map(p => ({
        imagePath: p.processedImagePath,
        ocrBlocks: p.ocr?.blocks,
        ocrImageWidth: p.ocr?.imageWidth,
        ocrImageHeight: p.ocr?.imageHeight,
      }));
      const built = await Pdf.buildPdf(pages, 'balanced', exportedPdfPath(dirs, doc.id, safeName), hasOcr);
      await Share.shareFile(built.path, 'application/pdf', doc.name);
    } catch (e) {
      Alert.alert('Share failed', friendlyErrorMessage(e));
    }
  }

  function confirmDelete(doc: Document) {
    Alert.alert('Delete document', `"${doc.name}" and all its pages will be permanently deleted.`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => remove(doc.id) },
    ]);
  }

  return (
    <ScreenContainer>
      <Header title="Library" onBack={() => navigation.goBack()} />

      <View style={styles.searchBar}>
        <Icon name="search" size={16} color={colors.textSecondary} />
        <TextInput
          value={query}
          onChangeText={setQuery}
          placeholder="Search documents by name…"
          placeholderTextColor={colors.textSecondary}
          style={styles.searchInput}
        />
      </View>

      <View style={styles.sortRow}>
        {(['recent', 'name', 'pages'] as SortMode[]).map(mode => (
          <Pressable key={mode} onPress={() => setSort(mode)} style={[styles.sortPill, sort === mode && styles.sortPillActive]}>
            <Text style={[styles.sortLabel, sort === mode && styles.sortLabelActive]}>
              {mode === 'recent' ? 'Recent' : mode === 'name' ? 'Name' : 'Pages'}
            </Text>
          </Pressable>
        ))}
      </View>

      {filtered.length === 0 ? (
        <EmptyState
          icon="folder"
          title={query ? 'No matching documents' : 'No documents yet'}
          message={query ? 'Try a different search term.' : 'Scan or import your first document from Home.'}
        />
      ) : (
        <SafeScrollView contentContainerStyle={styles.list}>
          {filtered.map(({ document, firstPage }) => (
            <LibraryRow
              key={document.id}
              document={document}
              firstPage={firstPage}
              onPress={() => navigation.navigate('DocumentEditor', { docId: document.id })}
              onMore={() => setSheetDocId(document.id)}
            />
          ))}
          <View style={styles.adSpacer}>
            <SafeAdContainer />
          </View>
        </SafeScrollView>
      )}

      <ActionSheet
        visible={activeDoc !== null}
        title={activeDoc?.name}
        onClose={() => setSheetDocId(null)}
        options={
          activeDoc
            ? [
                { key: 'open', label: 'Open', icon: 'document', onPress: () => navigation.navigate('DocumentEditor', { docId: activeDoc.id }) },
                { key: 'rename', label: 'Rename', icon: 'edit', onPress: () => setRenameDocId(activeDoc.id) },
                { key: 'duplicate', label: 'Duplicate', icon: 'duplicate', onPress: () => duplicate(activeDoc.id) },
                { key: 'share', label: 'Share as PDF', icon: 'share', onPress: () => handleShareDocument(activeDoc) },
                { key: 'delete', label: 'Delete', icon: 'trash', destructive: true, onPress: () => confirmDelete(activeDoc) },
              ]
            : []
        }
      />

      <RenameModal
        visible={renameDoc !== null}
        initialValue={renameDoc?.name ?? ''}
        onCancel={() => setRenameDocId(null)}
        onConfirm={async name => {
          if (renameDoc) await rename(renameDoc.id, name);
          setRenameDocId(null);
        }}
      />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  searchBar: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surface,
    borderRadius: radius.pill, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: spacing.lg, marginHorizontal: spacing.lg, marginBottom: spacing.sm,
  },
  searchInput: { flex: 1, marginLeft: spacing.sm, paddingVertical: spacing.md, fontSize: 15, color: colors.textPrimary },
  sortRow: { flexDirection: 'row', paddingHorizontal: spacing.lg, gap: spacing.sm, marginBottom: spacing.sm },
  sortPill: { paddingVertical: 6, paddingHorizontal: spacing.md, borderRadius: radius.pill, backgroundColor: colors.surfaceAlt },
  sortPillActive: { backgroundColor: colors.primary },
  sortLabel: { fontSize: 12, fontWeight: '700', color: colors.textSecondary },
  sortLabelActive: { color: colors.textInverse },
  list: { padding: spacing.lg, paddingTop: spacing.xs },
  card: {
    flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surface,
    borderRadius: radius.md, padding: spacing.md, marginBottom: spacing.md,
    borderWidth: 1, borderColor: colors.border,
  },
  thumbWrap: {
    width: 56, height: 72, borderRadius: radius.sm, backgroundColor: colors.surfaceAlt,
    alignItems: 'center', justifyContent: 'center', overflow: 'hidden', marginRight: spacing.md,
  },
  thumbImage: { width: '100%', height: '100%' },
  info: { flex: 1 },
  name: { fontSize: 15, fontWeight: '700', color: colors.textPrimary },
  meta: { fontSize: 12, color: colors.textSecondary, marginTop: 2 },
  moreButton: { padding: spacing.xs },
  adSpacer: { marginTop: spacing.md },
});
