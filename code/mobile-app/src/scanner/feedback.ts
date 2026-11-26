import { NativeModules, Platform } from 'react-native';
export function playReadTone() {
  if (Platform.OS === 'android')
    NativeModules.SmartStockScannerFeedback?.beep?.();
}
