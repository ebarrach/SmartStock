import React from 'react';
import renderer, { act, ReactTestRenderer } from 'react-test-renderer';
import { AppState, Linking } from 'react-native';
import { CameraPreview } from '../src/scanner/CameraPreview';

let mockPermission = 'denied';
let mockDevice: unknown = { id: 'rear', position: 'back' };
let mockScanner: {
  codeTypes: string[];
  onCodeScanned: (codes: unknown[]) => void;
};
jest.mock('react-native-vision-camera', () => {
  const { View } = require('react-native');
  return {
    Camera: Object.assign(View, {
      getCameraPermissionStatus: () => mockPermission,
      requestCameraPermission: async () => mockPermission,
    }),
    useCameraDevice: () => mockDevice,
    useCodeScanner: (config: typeof mockScanner) => {
      mockScanner = config;
      return config;
    },
  };
});
let tree: ReactTestRenderer;
beforeEach(() => {
  AppState.currentState = 'active';
});
afterEach(async () => {
  await act(async () => tree?.unmount());
  jest.restoreAllMocks();
});

test('permission refusee propose demande et reglages, sans ouvrir la camera', async () => {
  mockPermission = 'denied';
  mockDevice = { id: 'rear' };
  const settings = jest
    .spyOn(Linking, 'openSettings')
    .mockResolvedValue(undefined);
  await act(async () => {
    tree = renderer.create(<CameraPreview active onCodes={() => {}} />);
  });
  expect(JSON.stringify(tree.toJSON())).toContain('Autoriser la caméra');
  const button = tree.root.findAll(
    n =>
      n.props.accessibilityLabel === 'Ouvrir les réglages caméra' &&
      typeof n.props.onPress === 'function',
  )[0];
  await act(async () => button.props.onPress());
  expect(settings).toHaveBeenCalled();
});
test('lecture DataMatrix utilise le contenu brut ASCII29 et ignore les QR codes', async () => {
  mockPermission = 'granted';
  mockDevice = { id: 'rear' };
  const result = jest.fn();
  await act(async () => {
    tree = renderer.create(<CameraPreview active onCodes={result} />);
  });
  expect(mockScanner.codeTypes).toEqual(['data-matrix']);
  const raw = '010540071122334410USB\u001d21S1';
  await act(async () =>
    mockScanner.onCodeScanned([{ type: 'data-matrix', value: raw }]),
  );
  expect(result).toHaveBeenCalledWith([raw]);
  await act(async () =>
    mockScanner.onCodeScanned([{ type: 'qr', value: 'ignored' }]),
  );
  expect(result).toHaveBeenCalledTimes(1);
});
test('absence de camera arriere est expliquee', async () => {
  mockPermission = 'granted';
  mockDevice = undefined;
  await act(async () => {
    tree = renderer.create(<CameraPreview active onCodes={() => {}} />);
  });
  expect(JSON.stringify(tree.toJSON())).toContain('Aucune caméra arrière');
});
