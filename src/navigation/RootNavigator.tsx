import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { RootStackParamList } from './types';
import { HomeScreen } from '../screens/HomeScreen';
import { LibraryScreen } from '../screens/LibraryScreen';
import { ScannerScreen } from '../screens/ScannerScreen';
import { PageReviewScreen } from '../screens/PageReviewScreen';
import { DocumentEditorScreen } from '../screens/DocumentEditorScreen';
import { ExportScreen } from '../screens/ExportScreen';
import { OcrTextScreen } from '../screens/OcrTextScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { CropScreen } from '../screens/CropScreen';
import { colors } from '../theme/colors';

const Stack = createNativeStackNavigator<RootStackParamList>();

export function RootNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
        contentStyle: { backgroundColor: colors.background },
        animation: 'slide_from_right',
      }}>
      <Stack.Screen name="Home" component={HomeScreen} />
      <Stack.Screen name="Library" component={LibraryScreen} />
      <Stack.Screen
        name="Scanner"
        component={ScannerScreen}
        options={{ animation: 'fade', gestureEnabled: false }}
      />
      <Stack.Screen name="PageReview" component={PageReviewScreen} />
      <Stack.Screen name="DocumentEditor" component={DocumentEditorScreen} />
      <Stack.Screen name="Export" component={ExportScreen} />
      <Stack.Screen name="OcrText" component={OcrTextScreen} />
      <Stack.Screen name="Settings" component={SettingsScreen} />
      <Stack.Screen name="Crop" component={CropScreen} options={{ animation: 'fade' }} />
    </Stack.Navigator>
  );
}
