import React, { useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { ScreenContainer } from '../components/ScreenContainer';
import { SafeScrollView } from '../components/SafeScrollView';
import { SafeAdContainer } from '../components/SafeAdContainer';
import { Header } from '../components/Header';
import { PrimaryButton, SecondaryButton } from '../components/Button';
import { LoadingOverlay } from '../components/LoadingOverlay';
import { Icon } from '../components/Icon';
import { colors } from '../theme/colors';
import { radius, spacing } from '../theme/spacing';
import { typography } from '../theme/typography';
import { useDocument } from '../hooks/useDocument';
import { PdfQuality } from '../types/document';
import { Pdf } from '../native/Pdf';
import { DocumentProcessing } from '../native/DocumentProcessing';
import { Share } from '../native/Share';
import { getAppDirectories } from '../data/appDirs';
import { exportedPdfPath, exportedJpegPath } from '../data/paths';
import { sanitizeFileName, formatFileSize } from '../utils/format';
import { useSettingsStore } from '../state/settingsStore';
import { AdManager } from '../ads/AdManager';
import { friendlyErrorMessage } from '../utils/errors';

type Nav = NativeStackNavigationProp<RootStackParamList, 'Export'>;
type Rt = { params: RootStackParamList['Export'] };

const QUALITY_OPTIONS: { value: PdfQuality; label: string; hint: string }[] = [
  { value: 'original', label: 'Original Quality', hint: 'Largest file, best detail' },
  { value: 'balanced', label: 'Balanced', hint: 'Great quality, smaller file' },
  { value: 'smaller', label: 'Smaller File', hint: 'Best for emailing' },
];

export function ExportScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute() as Rt;
  const { docId } = route.params;
  const { document } = useDocument(docId);
  const { settings } = useSettingsStore();

  const [format, setFormat] = useState<'pdf' | 'jpeg'>('pdf');
  const [quality, setQuality] = useState<PdfQuality>(settings.defaultPdfQuality);
  const [searchable, setSearchable] = useState(true);
  const [working, setWorking] = useState<string | null>(null);
  const [pdfResult, setPdfResult] = useState<{ path: string; sizeBytes: number } | null>(null);
  const [jpegResults, setJpegResults] = useState<string[] | null>(null);

  if (!document) {
    return (
      <ScreenContainer>
        <Header title="Export" onBack={() => navigation.goBack()} />
      </ScreenContainer>
    );
  }

  const hasOcr = document.pages.some(p => p.ocr);

  async function runExport() {
    setPdfResult(null);
    setJpegResults(null);
    setWorking(format === 'pdf' ? 'Building your PDF…' : 'Exporting images…');
    try {
      const dirs = await getAppDirectories();
      const safeName = sanitizeFileName(document!.name);
      if (format === 'pdf') {
        const pages = document!.pages.map(p => ({
          imagePath: p.processedImagePath,
          ocrBlocks: p.ocr?.blocks,
          ocrImageWidth: p.ocr?.imageWidth,
          ocrImageHeight: p.ocr?.imageHeight,
        }));
        const outPath = exportedPdfPath(dirs, docId, safeName);
        const result = await Pdf.buildPdf(pages, quality, outPath, searchable && hasOcr);
        setPdfResult(result);
      } else {
        const jpegQuality = quality === 'original' ? 95 : quality === 'balanced' ? 82 : 60;
        const paths: string[] = [];
        for (let i = 0; i < document!.pages.length; i++) {
          const out = exportedJpegPath(dirs, safeName, i);
          const res = await DocumentProcessing.exportJpeg(document!.pages[i].processedImagePath, out, jpegQuality);
          paths.push(res.path);
        }
        setJpegResults(paths);
      }
      AdManager.recordCompletedSession('export_completed');
    } catch (e) {
      Alert.alert('Export failed', friendlyErrorMessage(e));
    } finally {
      setWorking(null);
    }
  }

  async function handleShare() {
    try {
      if (format === 'pdf' && pdfResult) {
        await Share.shareFile(pdfResult.path, 'application/pdf', document!.name);
      } else if (jpegResults && jpegResults.length > 0) {
        await Share.shareFiles(jpegResults, 'image/jpeg', document!.name);
      }
    } catch (e) {
      Alert.alert('Share failed', friendlyErrorMessage(e));
    }
  }

  async function handleSaveAs() {
    try {
      if (format === 'pdf' && pdfResult) {
        const res = await Share.saveAs(pdfResult.path, `${sanitizeFileName(document!.name)}.pdf`, 'application/pdf');
        if (!res.cancelled) Alert.alert('Saved', 'Your PDF has been saved.');
      } else if (jpegResults && jpegResults.length > 0) {
        // Images are saved one at a time so the user picks a destination for each.
        for (const path of jpegResults) {
          const name = path.split('/').pop() ?? 'page.jpg';
          const res = await Share.saveAs(path, name, 'image/jpeg');
          if (res.cancelled) break;
        }
        Alert.alert('Saved', 'Your images have been saved.');
      }
    } catch (e) {
      Alert.alert('Save failed', friendlyErrorMessage(e));
    }
  }

  async function handleOpenWith() {
    try {
      if (format === 'pdf' && pdfResult) {
        await Share.openFile(pdfResult.path, 'application/pdf');
      } else if (jpegResults && jpegResults.length > 0) {
        await Share.openFile(jpegResults[0], 'image/jpeg');
      }
    } catch (e) {
      Alert.alert('Open failed', friendlyErrorMessage(e));
    }
  }

  const hasResult = pdfResult !== null || (jpegResults !== null && jpegResults.length > 0);

  return (
    <ScreenContainer>
      <Header title="Export" subtitle={document.name} onBack={() => navigation.goBack()} />
      <SafeScrollView contentContainerStyle={styles.content}>
        <View style={styles.formatRow}>
          <Pressable
            style={[styles.formatCard, format === 'pdf' && styles.formatCardActive]}
            onPress={() => { setFormat('pdf'); setPdfResult(null); setJpegResults(null); }}>
            <Icon name="pdf" size={24} color={format === 'pdf' ? colors.primary : colors.textSecondary} />
            <Text style={[styles.formatLabel, format === 'pdf' && { color: colors.primary }]}>PDF</Text>
          </Pressable>
          <Pressable
            style={[styles.formatCard, format === 'jpeg' && styles.formatCardActive]}
            onPress={() => { setFormat('jpeg'); setPdfResult(null); setJpegResults(null); }}>
            <Icon name="gallery" size={24} color={format === 'jpeg' ? colors.primary : colors.textSecondary} />
            <Text style={[styles.formatLabel, format === 'jpeg' && { color: colors.primary }]}>JPEG Images</Text>
          </Pressable>
        </View>

        <Text style={typography.subtitle}>Quality</Text>
        <View style={styles.qualityList}>
          {QUALITY_OPTIONS.map(opt => (
            <Pressable key={opt.value} style={styles.qualityRow} onPress={() => setQuality(opt.value)}>
              <View style={[styles.radio, quality === opt.value && styles.radioActive]} />
              <View style={{ flex: 1 }}>
                <Text style={typography.bodyStrong}>{opt.label}</Text>
                <Text style={typography.caption}>{opt.hint}</Text>
              </View>
            </Pressable>
          ))}
        </View>

        {format === 'pdf' && hasOcr && (
          <Pressable style={styles.searchableRow} onPress={() => setSearchable(v => !v)}>
            <View style={[styles.checkbox, searchable && styles.checkboxActive]}>
              {searchable && <Icon name="check" size={12} color={colors.textInverse} />}
            </View>
            <Text style={typography.body}>Make PDF text searchable (from OCR)</Text>
          </Pressable>
        )}

        <Text style={styles.noWatermark}>No watermark — ever. PaperRescue is completely free.</Text>

        <PrimaryButton
          label={hasResult ? 'Export Again' : `Export ${format === 'pdf' ? 'PDF' : 'Images'}`}
          onPress={runExport}
        />

        {hasResult && (
          <View style={styles.resultCard}>
            <Text style={typography.bodyStrong}>
              {format === 'pdf' ? 'PDF ready' : `${jpegResults?.length} images ready`}
            </Text>
            {pdfResult && <Text style={typography.caption}>{formatFileSize(pdfResult.sizeBytes)}</Text>}
            <View style={styles.resultActions}>
              <View style={styles.resultButton}>
                <SecondaryButton label="Save Locally" icon="folder" onPress={handleSaveAs} />
              </View>
              <View style={styles.resultButton}>
                <SecondaryButton label="Share" icon="share" onPress={handleShare} />
              </View>
            </View>
            <View style={{ marginTop: spacing.sm }}>
              <SecondaryButton label="Open Using Another App" onPress={handleOpenWith} />
            </View>
          </View>
        )}

        <View style={styles.adSpacer}>
          <SafeAdContainer />
        </View>
      </SafeScrollView>
      <LoadingOverlay visible={working !== null} label={working ?? undefined} />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.lg },
  formatRow: { flexDirection: 'row', gap: spacing.md, marginBottom: spacing.lg },
  formatCard: {
    flex: 1, borderWidth: 1.5, borderColor: colors.border, borderRadius: radius.md,
    paddingVertical: spacing.lg, alignItems: 'center', backgroundColor: colors.surface,
  },
  formatCardActive: { borderColor: colors.primary, backgroundColor: colors.surfaceAlt },
  formatLabel: { marginTop: spacing.xs, fontWeight: '700', color: colors.textSecondary },
  qualityList: { marginTop: spacing.sm, marginBottom: spacing.lg },
  qualityRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: spacing.sm },
  radio: { width: 20, height: 20, borderRadius: 10, borderWidth: 2, borderColor: colors.border, marginRight: spacing.md },
  radioActive: { borderColor: colors.primary, backgroundColor: colors.primary },
  searchableRow: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.md },
  checkbox: {
    width: 20, height: 20, borderRadius: 5, borderWidth: 2, borderColor: colors.border,
    marginRight: spacing.md, alignItems: 'center', justifyContent: 'center',
  },
  checkboxActive: { backgroundColor: colors.primary, borderColor: colors.primary },
  noWatermark: { color: colors.success, fontSize: 12, fontWeight: '600', marginBottom: spacing.lg },
  resultCard: {
    marginTop: spacing.lg, padding: spacing.lg, borderRadius: radius.md,
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border,
  },
  resultActions: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md },
  resultButton: { flex: 1 },
  adSpacer: { marginTop: spacing.xl },
});
