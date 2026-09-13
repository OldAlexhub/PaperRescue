/**
 * PaperRescue: PDF Scanner & OCR
 * @format
 */

import React, { useEffect } from 'react';
import { StatusBar } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { NavigationContainer } from '@react-navigation/native';
import mobileAds from 'react-native-google-mobile-ads';
import { RootNavigator } from './src/navigation/RootNavigator';
import { AdManager } from './src/ads/AdManager';

function App() {
  useEffect(() => {
    mobileAds()
      .initialize()
      .then(() => AdManager.init())
      .catch(() => {
        // Ads failing to initialize must never block the app — scanning works regardless.
      });
  }, []);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <NavigationContainer>
        <RootNavigator />
      </NavigationContainer>
    </SafeAreaProvider>
  );
}

export default App;
