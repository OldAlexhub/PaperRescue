import React, { useState } from 'react';
import { Alert, Image, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { ScreenContainer } from '../components/ScreenContainer';
import { SafeBottomBar } from '../components/SafeBottomBar';
import { Header } from '../components/Header';
import { IconButton, PrimaryButton, SecondaryButton } from '../components/Button';
import { FilterModeSelector } from '../components/FilterModeSelector';
import { SliderControl } from '../components/SliderControl';
import { QualityScoreCard } from '../components/QualityScoreCard';
import { LoadingOverlay } from '../components/LoadingOverlay';
import { colors } from '../theme/colors';
import { spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { useDocument } from '../hooks/useDocument';
import { DocumentProcessing } from '../native/DocumentProcessing';
import { Quality } from '../native/Quality';
import { Ocr } from '../native/Ocr';
import * as repo from '../data/repository';
import { retakePage } from '../logic/pageIngest';
import { Scanner } from '../native/Scanner';
import { useSettingsStore } from '../state/settingsStore';
import { EnhanceSettings } from '../types/document';
import { friendlyErrorMessage } from '../utils/errors';
import { AdManager } from '../ads/AdManager';

type Nav = NativeStackNavigationProp<RootStackParamList, 'PageReview'>;
type Rt = { params: RootStackParamList['PageReview'] };

export function PageReviewScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute() as Rt;
  const { docId, pageId } = route.params;
  const { document, refresh } = useDocument(docId);
  const { settings } = useSettingsStore();
  const page = document?.pages.find(p => p.id === pageId);
  const pageIndex = document?.pages.findIndex(p => p.id === pageId) ?? -1;

  const [enhance, setEnhance] = useState<EnhanceSettings | null>(page?.enhance ?? null);
  const [previewVersion, setPreviewVersion] = useState(0);
  const [applying, setApplying] = useState(false);
  const [busyLabel, setBusyLabel] = useState<string | null>(null);
  const [dismissedWarning, setDismissedWarning] = useState(false);

  React.useEffect(() => {
    if (page && !enhance) setEnhance(page.enhance);
    // Only seed local state from the page once — subsequent edits are local until applyEnhance persists them.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  if (!document || !page || !enhance) {
    return (
      <ScreenContainer>
        <Header title="Page Review" onBack={() => navigation.goBack()} />
      </ScreenContainer>
    );
  }

  async function applyEnhance(next: EnhanceSettings) {
    if (!page) return;
    setEnhance(next);
    setApplying(true);
    try {
      await DocumentProcessing.enhance(page.baseImagePath, page.processedImagePath, next);
      await repo.updatePage(page.id, { enhance: next });
      setPreviewVersion(v => v + 1);
    } catch (e) {
      Alert.alert('Could not apply changes', friendlyErrorMessage(e));
    } finally {
      setApplying(false);
    }
  }

  async function finishAndRegenerateThumbnail() {
    if (!page) return;
    try {
      await DocumentProcessing.generateThumbnail(page.processedImagePath, page.thumbnailPath, 360);
    } catch {
      // Thumbnail regen failing is non-critical — the full page image is still correct.
    }
  }

  async function handleRescueAutomatically() {
    if (!page) return;
    setBusyLabel('Rescuing this page…');
    try {
      await DocumentProcessing.autoRescue(page.baseImagePath, page.processedImagePath);
      await DocumentProcessing.generateThumbnail(page.processedImagePath, page.thumbnailPath, 360);
      let ocrWordCount: number | undefined;
      if (settings.autoOcr) {
        try {
          const ocr = await Ocr.recognize(page.processedImagePath);
          ocrWordCount = ocr.wordCount;
          await repo.updatePage(page.id, { ocr: { ...ocr, computedAt: Date.now() } });
        } catch {}
      }
      const quality = await Quality.analyze(page.processedImagePath, ocrWordCount);
      await repo.updatePage(page.id, { quality: { ...quality, computedAt: Date.now() } });
      setPreviewVersion(v => v + 1);
      setDismissedWarning(false);
      await refresh();
    } catch (e) {
      Alert.alert('Rescue failed', friendlyErrorMessage(e));
    } finally {
      setBusyLabel(null);
    }
  }

  async function handleRetake() {
    if (!page) return;
    const mode = page.rescue ? 'rescue' : 'single';
    try {
      const result = await Scanner.startScan(mode, pageIndex + 1);
      if (result.status !== 'captured') return;
      setBusyLabel('Preparing your page…');
      await retakePage(
        page.id,
        {
          basePath: result.correctedImagePath,
          rawPath: result.rawImagePath,
          corners: result.corners,
          rescue: result.rescueReport ?? null,
        },
        settings,
      );
      setPreviewVersion(v => v + 1);
      setDismissedWarning(false);
      await refresh();
    } catch (e) {
      Alert.alert('Retake failed', friendlyErrorMessage(e));
    } finally {
      setBusyLabel(null);
    }
  }

  async function handleAddAnotherPage() {
    await finishAndRegenerateThumbnail();
    navigation.navigate('Scanner', {
      docId,
      pageNumber: (document?.pages.length ?? 0) + 1,
      mode: page?.rescue ? 'rescue' : 'single',
    });
  }

  async function handleDone() {
    await finishAndRegenerateThumbnail();
    AdManager.recordCompletedSession('scan_saved');
    navigation.navigate('DocumentEditor', { docId });
  }

  const showQualityActions = page.quality && page.quality.recommendation !== 'good' && !dismissedWarning;
  const imageUri = `file://${page.processedImagePath}?v=${previewVersion}`;

  return (
    <ScreenContainer>
      <Header
        title="Review Page"
        subtitle={`Page ${pageIndex + 1} of ${document.pages.length}`}
        onBack={() => navigation.navigate('DocumentEditor', { docId })}
        right={<IconButton name="crop" accessibilityLabel="Crop" onPress={() => navigation.navigate('Crop', { docId, pageId })} />}
      />

      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <View style={styles.previewWrap}>
          <Image source={{ uri: imageUri }} style={styles.preview} resizeMode="contain" />
          {applying && (
            <View style={styles.previewOverlay}>
              <Text style={styles.previewOverlayText}>Applying…</Text>
            </View>
          )}
        </View>

        <Text style={typography.subtitle}>Filter</Text>
        <View style={styles.section}>
          <FilterModeSelector value={enhance.mode} onChange={mode => applyEnhance({ ...enhance, mode })} />
        </View>

        <Text style={typography.subtitle}>Adjust</Text>
        <View style={styles.section}>
          <SliderControl
            label="Brightness"
            value={enhance.brightness}
            minimumValue={-100}
            maximumValue={100}
            onValueChange={v => setEnhance({ ...enhance, brightness: v })}
            onSlidingComplete={v => applyEnhance({ ...enhance, brightness: v })}
          />
          <SliderControl
            label="Contrast"
            value={enhance.contrast}
            minimumValue={-100}
            maximumValue={100}
            onValueChange={v => setEnhance({ ...enhance, contrast: v })}
            onSlidingComplete={v => applyEnhance({ ...enhance, contrast: v })}
          />
          <SliderControl
            label="Sharpen"
            value={enhance.sharpen}
            minimumValue={0}
            maximumValue={100}
            onValueChange={v => setEnhance({ ...enhance, sharpen: v })}
            onSlidingComplete={v => applyEnhance({ ...enhance, sharpen: v })}
          />
          <SliderControl
            label="Noise Reduction"
            value={enhance.denoise}
            minimumValue={0}
            maximumValue={100}
            onValueChange={v => setEnhance({ ...enhance, denoise: v })}
            onSlidingComplete={v => applyEnhance({ ...enhance, denoise: v })}
          />

          <View style={styles.toggleRow}>
            <Text style={typography.body}>Whiten background</Text>
            <Switch
              value={enhance.whitenBackground}
              onValueChange={v => applyEnhance({ ...enhance, whitenBackground: v })}
              trackColor={{ true: colors.primary }}
            />
          </View>
          <View style={styles.toggleRow}>
            <Text style={typography.body}>Reduce shadow</Text>
            <Switch
              value={enhance.reduceShadow}
              onValueChange={v => applyEnhance({ ...enhance, reduceShadow: v })}
              trackColor={{ true: colors.primary }}
            />
          </View>
        </View>

        <Text style={typography.subtitle}>Scan Quality</Text>
        <View style={styles.section}>
          <QualityScoreCard report={page.quality} />
        </View>

        {showQualityActions && (
          <View style={styles.qualityActions}>
            <PrimaryButton label="Rescue Automatically" variant="rescue" icon="rescue" onPress={handleRescueAutomatically} />
            <View style={{ height: spacing.sm }} />
            <SecondaryButton label="Retake" icon="rotateLeft" onPress={handleRetake} />
            <View style={{ height: spacing.sm }} />
            <SecondaryButton label="Keep Anyway" onPress={() => setDismissedWarning(true)} />
          </View>
        )}
      </ScrollView>

      <SafeBottomBar>
        <View style={styles.footerRow}>
          <View style={styles.footerHalf}>
            <SecondaryButton label="Add Another Page" icon="plus" onPress={handleAddAnotherPage} />
          </View>
          <View style={[styles.footerHalf, { marginLeft: spacing.sm }]}>
            <PrimaryButton label="Done" icon="check" onPress={handleDone} />
          </View>
        </View>
      </SafeBottomBar>

      <LoadingOverlay visible={busyLabel !== null} label={busyLabel ?? undefined} />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.lg, paddingBottom: spacing.xxl },
  previewWrap: {
    height: 320,
    backgroundColor: colors.surfaceAlt,
    borderRadius: 12,
    marginBottom: spacing.lg,
    overflow: 'hidden',
  },
  preview: { width: '100%', height: '100%' },
  previewOverlay: {
    position: 'absolute', bottom: 8, alignSelf: 'center',
    backgroundColor: 'rgba(0,0,0,0.6)', borderRadius: 12, paddingHorizontal: 12, paddingVertical: 4,
  },
  previewOverlayText: { color: '#fff', fontSize: 12, fontWeight: '600' },
  section: { marginTop: spacing.sm, marginBottom: spacing.lg },
  toggleRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: spacing.xs },
  qualityActions: { marginBottom: spacing.lg },
  footerRow: { flexDirection: 'row' },
  footerHalf: { flex: 1 },
});
