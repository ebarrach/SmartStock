import React, { useCallback, useEffect, useState } from 'react';
import { AppState, Linking, StyleSheet, Text, View } from 'react-native';
import {
  Camera,
  CameraPermissionStatus,
  useCameraDevice,
  useCodeScanner,
} from 'react-native-vision-camera';
import { Action, Message } from '../ui/components';

export function CameraPreview({
  active,
  onCodes,
}: {
  active: boolean;
  onCodes: (values: string[]) => void;
}) {
  const device = useCameraDevice('back');
  const [permission, setPermission] = useState<CameraPermissionStatus>(
    Camera.getCameraPermissionStatus(),
  );
  const [foreground, setForeground] = useState(
    AppState.currentState === 'active',
  );
  const [error, setError] = useState('');
  const request = useCallback(async () => {
    try {
      setPermission(await Camera.requestCameraPermission());
    } catch {
      setError('Impossible de demander l’autorisation caméra.');
    }
  }, []);
  useEffect(() => {
    if (active && Camera.getCameraPermissionStatus() === 'not-determined')
      request();
    const subscription = AppState.addEventListener('change', state => {
      setForeground(state === 'active');
      if (state === 'active') setPermission(Camera.getCameraPermissionStatus());
    });
    return () => subscription.remove();
  }, [active, request]);
  const scanner = useCodeScanner({
    codeTypes: ['data-matrix'],
    onCodeScanned: codes => {
      if (!active || !foreground) return;
      const values = codes
        .filter(
          code => code.type === 'data-matrix' && typeof code.value === 'string',
        )
        .map(code => code.value!);
      if (values.length) onCodes(values);
    },
  });
  if (permission !== 'granted')
    return (
      <View style={styles.permission}>
        <Message
          error
          text="La caméra est nécessaire pour lire les DataMatrix. Aucun microphone ni accès aux photos n’est demandé."
        />
        <Action label="Autoriser la caméra" onPress={request} />
        <Action
          secondary
          label="Ouvrir les réglages caméra"
          onPress={() => {
            Linking.openSettings().catch(() =>
              setError(
                'Ouvrez les autorisations de SmartStock dans les paramètres Android.',
              ),
            );
          }}
        />
        {!!error && <Message error text={error} />}
      </View>
    );
  if (!device)
    return (
      <Message
        error
        text="Aucune caméra arrière disponible sur cet appareil."
      />
    );
  if (error)
    return (
      <View>
        <Message error text={error} />
        <Action label="Relancer la caméra" onPress={() => setError('')} />
      </View>
    );
  return (
    <View style={styles.preview}>
      <Camera
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={active && foreground}
        codeScanner={scanner}
        onError={failure =>
          setError(`Caméra indisponible : ${failure.message}`)
        }
      />
      <View pointerEvents="none" style={styles.frame} />
      <Text pointerEvents="none" style={styles.caption}>
        Centrez un seul DataMatrix
      </Text>
    </View>
  );
}
const styles = StyleSheet.create({
  permission: { gap: 12 },
  preview: {
    height: 310,
    borderRadius: 18,
    overflow: 'hidden',
    backgroundColor: '#071e30',
  },
  frame: {
    position: 'absolute',
    alignSelf: 'center',
    top: 55,
    width: 200,
    height: 200,
    borderWidth: 3,
    borderColor: '#3ae3cd',
    borderRadius: 12,
  },
  caption: {
    color: 'white',
    position: 'absolute',
    bottom: 12,
    alignSelf: 'center',
    backgroundColor: '#071e30',
  },
});
