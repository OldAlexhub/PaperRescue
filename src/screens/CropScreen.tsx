import React, { useMemo, useRef, useState } from 'react';
import { Alert, Image, LayoutChangeEvent, PanResponder, StyleSheet, Text, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { ScreenContainer } from '../components/ScreenContainer';
import { Header } from '../components/Header';
import { SafeBottomBar } from '../components/SafeBottomBar';
import { PrimaryButton, SecondaryButton } from '../components/Button';
import { LoadingOverlay } from '../components/LoadingOverlay';
import { colors } from '../theme/colors';
import { spacing } from '../theme/spacing';
import { DocumentProcessing } from '../native/DocumentProcessing';
import { FileSystem } from '../native/FileSystem';
import * as repo from '../data/repository';
import { useDocument } from '../hooks/useDocument';
import { generateId } from '../utils/id';
import { getAppDirectories } from '../data/appDirs';
import { capturePath } from '../data/paths';
import { friendlyErrorMessage } from '../utils/errors';

type Nav = NativeStackNavigationProp<RootStackParamList, 'Crop'>;
type Rt = { params: RootStackParamList['Crop'] };

type NormalizedPoint = { x: number; y: number };
type Rect = { x: number; y: number; width: number; height: number };
const DEFAULT_CORNERS: NormalizedPoint[] = [
  { x: 0.08, y: 0.08 },
  { x: 0.92, y: 0.08 },
  { x: 0.92, y: 0.92 },
  { x: 0.08, y: 0.92 },
];

/** Interactive manual crop: drag 4 corner handles over the raw capture, then re-runs perspective correction. */
export function CropScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute() as Rt;
  const { docId, pageId } = route.params;
  const { document } = useDocument(docId);
  const page = document?.pages.find(p => p.id === pageId);

  const [containerSize, setContainerSize] = useState({ width: 0, height: 0 });
  const [sourceSize, setSourceSize] = useState({ width: 0, height: 0 });
  const [corners, setCorners] = useState<NormalizedPoint[]>(
    page?.corners && page.corners.length === 8
      ? orderCorners([0, 1, 2, 3].map(i => ({ x: page.corners![i * 2], y: page.corners![i * 2 + 1] })))
      : DEFAULT_CORNERS,
  );
  const seededPageId = useRef(page?.id ?? null);
  const cornersRef = useRef(corners);
  cornersRef.current = corners;
  const [saving, setSaving] = useState(false);

  const sourcePath = page?.rawImagePath ?? page?.baseImagePath;

  React.useEffect(() => {
    if (!page || seededPageId.current === page.id) return;
    const detected = page.corners && page.corners.length === 8
      ? orderCorners([0, 1, 2, 3].map(i => ({ x: page.corners![i * 2], y: page.corners![i * 2 + 1] })))
      : DEFAULT_CORNERS;
    seededPageId.current = page.id;
    setCorners(detected);
  }, [page]);

  function onContainerLayout(e: LayoutChangeEvent) {
    const { width, height } = e.nativeEvent.layout;
    setContainerSize({ width, height });
  }

  const imageRect = useMemo(
    () => containRect(containerSize.width, containerSize.height, sourceSize.width, sourceSize.height),
    [containerSize, sourceSize],
  );

  // PanResponder reports cumulative dx/dy from gesture start, so we need a stable
  // "start" snapshot per gesture rather than applying dx/dy directly each move.
  const gestureStart = useRef<NormalizedPoint[]>(corners);
  const responders = useMemo(
    () =>
      [0, 1, 2, 3].map(index =>
        PanResponder.create({
          onStartShouldSetPanResponder: () => true,
          onPanResponderGrant: () => {
            gestureStart.current = cornersRef.current;
          },
          onPanResponderMove: (_evt, gesture) => {
            if (imageRect.width <= 0 || imageRect.height <= 0) return;
            setCorners(prev => {
              const next = [...prev];
              const base = gestureStart.current[index];
              const proposed = {
                x: clamp01(base.x + gesture.dx / imageRect.width),
                y: clamp01(base.y + gesture.dy / imageRect.height),
              };
              next[index] = proposed;
              return isValidQuad(next) ? next : prev;
            });
          },
        }),
      ),
    [imageRect.width, imageRect.height],
  );

  async function handleConfirm() {
    if (!page || !sourcePath || imageRect.width === 0 || !isValidQuad(corners)) return;
    setSaving(true);
    try {
      const flat: number[] = [];
      corners.forEach(c => { flat.push(c.x, c.y); });
      const dirs = await getAppDirectories();
      const outPath = capturePath(dirs, `${generateId()}_crop.jpg`);
      const warped = await DocumentProcessing.warpPerspective(sourcePath, flat, outPath);
      // Overwrite the page's base image in place, then re-render the current filter on top of it.
      await FileSystem.copyFile(warped.path, page.baseImagePath);
      await DocumentProcessing.enhance(page.baseImagePath, page.processedImagePath, page.enhance);
      await DocumentProcessing.generateThumbnail(page.processedImagePath, page.thumbnailPath, 360);
      await repo.updatePage(pageId, { corners: flat });
      navigation.goBack();
    } catch (e) {
      Alert.alert('Crop failed', friendlyErrorMessage(e));
    } finally {
      setSaving(false);
    }
  }

  if (!page || !sourcePath) {
    return (
      <ScreenContainer>
        <Header title="Crop" onBack={() => navigation.goBack()} />
        <Text style={styles.missing}>This page has no original image to re-crop.</Text>
      </ScreenContainer>
    );
  }

  return (
    <ScreenContainer edges={['top', 'left', 'right']} background={colors.scannerBackground}>
      <Header title="Adjust Crop" onBack={() => navigation.goBack()} />
      <Text style={styles.hint}>Drag the corners to match the page edges</Text>
      <View style={styles.imageWrap} onLayout={onContainerLayout}>
        <Image
          source={{ uri: `file://${sourcePath}` }}
          style={styles.image}
          resizeMode="contain"
          onLoad={event => {
            const { width, height } = event.nativeEvent.source;
            setSourceSize({ width, height });
          }}
        />
        {imageRect.width > 0 && (
          <>
            {buildEdges(corners, imageRect).map((edge, i) => (
              <View key={i} style={edge} />
            ))}
            {corners.map((c, i) => (
              <View
                key={i}
                {...responders[i].panHandlers}
                style={[
                  styles.handle,
                  {
                    left: imageRect.x + c.x * imageRect.width - 16,
                    top: imageRect.y + c.y * imageRect.height - 16,
                  },
                ]}
              />
            ))}
          </>
        )}
      </View>
      <SafeBottomBar style={{ backgroundColor: colors.scannerBackground, borderTopColor: '#222' }}>
        <View style={styles.buttonRow}>
          <View style={styles.buttonHalf}>
            <SecondaryButton label="Cancel" onPress={() => navigation.goBack()} />
          </View>
          <View style={[styles.buttonHalf, { marginLeft: spacing.sm }]}>
            <PrimaryButton label="Apply Crop" onPress={handleConfirm} loading={saving} />
          </View>
        </View>
      </SafeBottomBar>
      <LoadingOverlay visible={saving} label="Applying crop…" />
    </ScreenContainer>
  );
}

function clamp01(v: number) {
  return Math.max(0, Math.min(1, v));
}

function containRect(containerWidth: number, containerHeight: number, imageWidth: number, imageHeight: number): Rect {
  if (containerWidth <= 0 || containerHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
    return { x: 0, y: 0, width: 0, height: 0 };
  }
  const scale = Math.min(containerWidth / imageWidth, containerHeight / imageHeight);
  const width = imageWidth * scale;
  const height = imageHeight * scale;
  return { x: (containerWidth - width) / 2, y: (containerHeight - height) / 2, width, height };
}

function polygonArea(points: NormalizedPoint[]) {
  return Math.abs(points.reduce((sum, point, index) => {
    const next = points[(index + 1) % points.length];
    return sum + point.x * next.y - next.x * point.y;
  }, 0)) / 2;
}

function isValidQuad(points: NormalizedPoint[]) {
  if (points.length !== 4 || polygonArea(points) < 0.025) return false;
  let winding = 0;
  for (let i = 0; i < 4; i++) {
    const a = points[i];
    const b = points[(i + 1) % 4];
    const c = points[(i + 2) % 4];
    const cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
    if (Math.abs(cross) < 0.002) return false;
    const sign = Math.sign(cross);
    if (winding !== 0 && sign !== winding) return false;
    winding = sign;
    if (Math.hypot(b.x - a.x, b.y - a.y) < 0.045) return false;
  }
  return true;
}

function orderCorners(points: NormalizedPoint[]): NormalizedPoint[] {
  if (points.length !== 4) return DEFAULT_CORNERS;
  const center = {
    x: points.reduce((sum, point) => sum + point.x, 0) / 4,
    y: points.reduce((sum, point) => sum + point.y, 0) / 4,
  };
  let cycle = [...points].sort(
    (a, b) => Math.atan2(a.y - center.y, a.x - center.x) - Math.atan2(b.y - center.y, b.x - center.x),
  );
  const signedArea = cycle.reduce((sum, point, index) => {
    const next = cycle[(index + 1) % 4];
    return sum + point.x * next.y - next.x * point.y;
  }, 0);
  if (signedArea < 0) cycle = cycle.reverse();
  let topEdge = 0;
  for (let i = 1; i < 4; i++) {
    const midpointY = (cycle[i].y + cycle[(i + 1) % 4].y) / 2;
    const bestY = (cycle[topEdge].y + cycle[(topEdge + 1) % 4].y) / 2;
    if (midpointY < bestY) topEdge = i;
  }
  const a = cycle[topEdge];
  const b = cycle[(topEdge + 1) % 4];
  const ordered = a.x <= b.x
    ? [a, b, cycle[(topEdge + 2) % 4], cycle[(topEdge + 3) % 4]]
    : [b, a, cycle[(topEdge + 3) % 4], cycle[(topEdge + 2) % 4]];
  return isValidQuad(ordered) ? ordered : DEFAULT_CORNERS;
}

function buildEdges(corners: NormalizedPoint[], layout: Rect) {
  const px = (c: NormalizedPoint) => ({
    x: layout.x + c.x * layout.width,
    y: layout.y + c.y * layout.height,
  });
  const styles_: any[] = [];
  for (let i = 0; i < 4; i++) {
    const a = px(corners[i]);
    const b = px(corners[(i + 1) % 4]);
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const length = Math.sqrt(dx * dx + dy * dy);
    const angle = Math.atan2(dy, dx);
    styles_.push({
      position: 'absolute',
      left: a.x,
      top: a.y - 1.5,
      width: length,
      height: 3,
      backgroundColor: colors.rescue,
      transform: [{ translateX: 0 }, { rotate: `${angle}rad` }],
      transformOrigin: '0 50%',
    });
  }
  return styles_;
}

const styles = StyleSheet.create({
  hint: { color: '#DDD', textAlign: 'center', marginBottom: spacing.sm, fontSize: 13 },
  imageWrap: { flex: 1, marginHorizontal: spacing.md },
  image: { width: '100%', height: '100%' },
  handle: {
    position: 'absolute',
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: 'rgba(255,122,41,0.35)',
    borderWidth: 2,
    borderColor: colors.rescue,
  },
  buttonRow: { flexDirection: 'row' },
  buttonHalf: { flex: 1 },
  missing: { color: colors.textSecondary, textAlign: 'center', marginTop: spacing.xl },
});
