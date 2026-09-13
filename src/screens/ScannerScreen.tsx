import React, { useEffect, useRef, useState } from 'react';
import { Alert, StyleSheet, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { colors } from '../theme/colors';
import { LoadingOverlay } from '../components/LoadingOverlay';
import { Scanner } from '../native/Scanner';
import { Gallery } from '../native/Gallery';
import { finalizePage, ingestImportedImage } from '../logic/pageIngest';
import * as repo from '../data/repository';
import { useSettingsStore } from '../state/settingsStore';
import { friendlyErrorMessage } from '../utils/errors';

type Nav = NativeStackNavigationProp<RootStackParamList, 'Scanner'>;
type Rt = { params: RootStackParamList['Scanner'] };

export function ScannerScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute() as Rt;
  const { docId, pageNumber, mode } = route.params;
  const { settings, loaded, load } = useSettingsStore();
  const [processingLabel, setProcessingLabel] = useState<string | null>(null);
  const startedRef = useRef(false);

  useEffect(() => {
    if (!loaded) load();
  }, [loaded]);

  useEffect(() => {
    if (!loaded || startedRef.current) return;
    startedRef.current = true;
    runScan();
  }, [loaded]);

  async function abandonIfEmptyAndGoBack() {
    const doc = await repo.getDocumentWithPages(docId);
    if (doc && doc.pages.length === 0) {
      await repo.deleteDocument(docId);
    }
    navigation.goBack();
  }

  async function runScan() {
    try {
      const result = await Scanner.startScan(mode, pageNumber);
      if (result.status === 'captured') {
        setProcessingLabel(mode === 'rescue' ? 'Finishing your Rescue Scan…' : 'Preparing your page…');
        const page = await finalizePage(
          docId,
          {
            basePath: result.correctedImagePath,
            rawPath: result.rawImagePath,
            corners: result.corners,
            rescue: result.rescueReport ?? null,
          },
          settings,
        );
        setProcessingLabel(null);
        navigation.replace('PageReview', { docId, pageId: page.id });
      } else if (result.status === 'use_gallery') {
        const picked = await Gallery.pickImages(1);
        if (picked.length > 0) {
          setProcessingLabel('Preparing your page…');
          const page = await ingestImportedImage(docId, picked[0], settings);
          setProcessingLabel(null);
          navigation.replace('PageReview', { docId, pageId: page.id });
        } else {
          runScan();
        }
      } else {
        await abandonIfEmptyAndGoBack();
      }
    } catch (e) {
      setProcessingLabel(null);
      Alert.alert('Scan failed', friendlyErrorMessage(e), [{ text: 'OK', onPress: abandonIfEmptyAndGoBack }]);
    }
  }

  return (
    <View style={styles.container}>
      <LoadingOverlay visible={processingLabel !== null} label={processingLabel ?? undefined} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.scannerBackground },
});
