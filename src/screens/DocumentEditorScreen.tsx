import React, { useState } from 'react';
import { Alert, Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { ScreenContainer } from '../components/ScreenContainer';
import { SafeScrollView } from '../components/SafeScrollView';
import { SafeBottomBar } from '../components/SafeBottomBar';
import { Header } from '../components/Header';
import { Icon } from '../components/Icon';
import { IconButton, PrimaryButton } from '../components/Button';
import { RenameModal } from '../components/RenameModal';
import { LoadingOverlay } from '../components/LoadingOverlay';
import { colors, qualityColor } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { useDocument } from '../hooks/useDocument';
import { Page } from '../types/document';
import * as repo from '../data/repository';
import { DocumentProcessing } from '../native/DocumentProcessing';
import { Gallery } from '../native/Gallery';
import { ingestImportedImage } from '../logic/pageIngest';
import { useSettingsStore } from '../state/settingsStore';
import { useDocumentStore } from '../state/documentStore';
import { friendlyErrorMessage } from '../utils/errors';

type Nav = NativeStackNavigationProp<RootStackParamList, 'DocumentEditor'>;
type Rt = { params: RootStackParamList['DocumentEditor'] };

export function DocumentEditorScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute() as Rt;
  const { docId } = route.params;
  const { document, refresh } = useDocument(docId);
  const { settings } = useSettingsStore();
  const { refresh: refreshLibrary } = useDocumentStore();
  const [reorderMode, setReorderMode] = useState(false);
  const [renameVisible, setRenameVisible] = useState(false);
  const [busyLabel, setBusyLabel] = useState<string | null>(null);

  if (!document) {
    return (
      <ScreenContainer>
        <Header title="Document" onBack={() => navigation.goBack()} />
      </ScreenContainer>
    );
  }

  async function move(index: number, direction: -1 | 1) {
    if (!document) return;
    const order = document.pages.map(p => p.id);
    const target = index + direction;
    if (target < 0 || target >= order.length) return;
    [order[index], order[target]] = [order[target], order[index]];
    await repo.reorderPages(docId, order);
    refresh();
  }

  async function rotate(page: Page) {
    setBusyLabel('Rotating page…');
    try {
      const nextRotation = ((page.rotation + 90) % 360) as 0 | 90 | 180 | 270;
      await DocumentProcessing.rotateImage(page.baseImagePath, page.baseImagePath, 90);
      await DocumentProcessing.enhance(page.baseImagePath, page.processedImagePath, page.enhance);
      await DocumentProcessing.generateThumbnail(page.processedImagePath, page.thumbnailPath, 360);
      await repo.updatePage(page.id, { rotation: nextRotation });
      await refresh();
    } catch (e) {
      Alert.alert('Rotate failed', friendlyErrorMessage(e));
    } finally {
      setBusyLabel(null);
    }
  }

  async function duplicate(page: Page) {
    setBusyLabel('Duplicating page…');
    try {
      await repo.duplicatePage(docId, page.id);
      await refresh();
    } finally {
      setBusyLabel(null);
    }
  }

  function confirmDelete(page: Page) {
    Alert.alert('Delete page', 'This page will be permanently removed.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete',
        style: 'destructive',
        onPress: async () => {
          const result = await repo.deletePage(docId, page.id);
          if (result.documentDeleted) {
            await refreshLibrary();
            navigation.navigate('Library');
          } else {
            await refresh();
          }
        },
      },
    ]);
  }

  async function addPage() {
    navigation.navigate('Scanner', { docId, pageNumber: document.pages.length + 1, mode: 'single' });
  }

  async function importMorePhotos() {
    try {
      const picked = await Gallery.pickImages(50);
      if (picked.length === 0) return;
      setBusyLabel('Importing photos…');
      for (const path of picked) {
        await ingestImportedImage(docId, path, settings);
      }
      await refresh();
      await refreshLibrary();
    } catch (e) {
      Alert.alert('Import failed', friendlyErrorMessage(e));
    } finally {
      setBusyLabel(null);
    }
  }

  return (
    <ScreenContainer>
      <Header
        title={document.name}
        subtitle={`${document.pages.length} ${document.pages.length === 1 ? 'page' : 'pages'}`}
        onBack={() => navigation.navigate('Library')}
        right={
          <IconButton name="edit" accessibilityLabel="Rename document" onPress={() => setRenameVisible(true)} />
        }
      />

      <View style={styles.toolbar}>
        <Pressable style={styles.toolbarButton} onPress={() => setReorderMode(v => !v)}>
          <Icon name="sort" size={18} color={reorderMode ? colors.primary : colors.textSecondary} />
          <Text style={[styles.toolbarLabel, reorderMode && { color: colors.primary }]}>Reorder</Text>
        </Pressable>
        <Pressable style={styles.toolbarButton} onPress={() => navigation.navigate('OcrText', { docId })}>
          <Icon name="text" size={18} color={colors.textSecondary} />
          <Text style={styles.toolbarLabel}>OCR Text</Text>
        </Pressable>
        <Pressable style={styles.toolbarButton} onPress={importMorePhotos}>
          <Icon name="gallery" size={18} color={colors.textSecondary} />
          <Text style={styles.toolbarLabel}>Import</Text>
        </Pressable>
      </View>

      <SafeScrollView extraBottomPadding={90} contentContainerStyle={styles.grid}>
        <View style={styles.pagesRow}>
          {document.pages.map((page, index) => (
            <View key={page.id} style={styles.pageCard}>
              <Pressable onPress={() => navigation.navigate('PageReview', { docId, pageId: page.id })}>
                <Image source={{ uri: `file://${page.thumbnailPath}` }} style={styles.pageThumb} resizeMode="cover" />
                {page.quality && (
                  <View style={[styles.qualityDot, { backgroundColor: qualityColor(page.quality.score) }]} />
                )}
                <View style={styles.pageNumberBadge}>
                  <Text style={styles.pageNumberText}>{index + 1}</Text>
                </View>
              </Pressable>

              {reorderMode ? (
                <View style={styles.reorderRow}>
                  <IconButton name="chevronUp" size={16} accessibilityLabel="Move up" onPress={() => move(index, -1)} />
                  <IconButton name="chevronDown" size={16} accessibilityLabel="Move down" onPress={() => move(index, 1)} />
                </View>
              ) : (
                <View style={styles.actionRow}>
                  <IconButton name="rotateRight" size={16} accessibilityLabel="Rotate" onPress={() => rotate(page)} />
                  <IconButton name="duplicate" size={16} accessibilityLabel="Duplicate" onPress={() => duplicate(page)} />
                  <IconButton name="trash" size={16} accessibilityLabel="Delete" color={colors.danger} onPress={() => confirmDelete(page)} />
                </View>
              )}
            </View>
          ))}

          <Pressable style={styles.addCard} onPress={addPage}>
            <Icon name="plus" size={22} color={colors.primary} />
            <Text style={styles.addLabel}>Add Page</Text>
          </Pressable>
        </View>
      </SafeScrollView>

      <SafeBottomBar>
        <PrimaryButton label="Export Document" icon="pdf" onPress={() => navigation.navigate('Export', { docId })} />
      </SafeBottomBar>

      <RenameModal
        visible={renameVisible}
        initialValue={document.name}
        onCancel={() => setRenameVisible(false)}
        onConfirm={async name => {
          await repo.renameDocument(docId, name);
          setRenameVisible(false);
          await refresh();
          await refreshLibrary();
        }}
      />
      <LoadingOverlay visible={busyLabel !== null} label={busyLabel ?? undefined} />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  toolbar: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingVertical: spacing.sm,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  toolbarButton: { alignItems: 'center' },
  toolbarLabel: { fontSize: 11, color: colors.textSecondary, marginTop: 2, fontWeight: '600' },
  grid: { padding: spacing.lg },
  pagesRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  pageCard: { width: '30%' },
  pageThumb: {
    width: '100%',
    aspectRatio: 0.75,
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceAlt,
    borderWidth: 1,
    borderColor: colors.border,
  },
  qualityDot: { position: 'absolute', top: 6, right: 6, width: 10, height: 10, borderRadius: 5 },
  pageNumberBadge: {
    position: 'absolute', bottom: 6, left: 6, backgroundColor: 'rgba(0,0,0,0.6)',
    borderRadius: 4, paddingHorizontal: 6, paddingVertical: 1,
  },
  pageNumberText: { color: '#fff', fontSize: 10, fontWeight: '700' },
  reorderRow: { flexDirection: 'row', justifyContent: 'space-around', marginTop: 4 },
  actionRow: { flexDirection: 'row', justifyContent: 'space-around', marginTop: 4 },
  addCard: {
    width: '30%', aspectRatio: 0.75, borderRadius: radius.sm, borderWidth: 1.5,
    borderColor: colors.border, borderStyle: 'dashed', alignItems: 'center', justifyContent: 'center',
  },
  addLabel: { fontSize: 11, color: colors.primary, fontWeight: '700', marginTop: 4 },
});
