import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Switch, Text, View } from 'react-native';
import { Action, Card, Field, Message, Title } from './components';
import { operationUUID } from '../scanner/identifiers';

type Scale = {
  source: string;
  detected: boolean;
  samples: number;
  raw: number;
  observedAt: string;
};
type Settings = {
  withoutSensors: boolean;
  persistent: boolean;
  scales: Scale[];
  tare?: { source: string; raw: number; id: string } | null;
  calibration?: {
    source: string;
    referenceGrams: number;
    measuredAt: string;
  } | null;
};
type Pending = { path: string; method: string; data: object; id: string };

export function ServerSettingsPanel({
  baseUrl,
  onChanged,
}: {
  baseUrl: string;
  onChanged: () => void;
}) {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [mass, setMass] = useState('');
  const [source, setSource] = useState('');
  const [pending, setPending] = useState<Pending | null>(null);
  const running = useRef(false);
  const request = useCallback(
    async (path: string, command?: Pending) => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 8000);
      try {
        const response = await fetch(
          `${baseUrl.replace(/\/$/, '')}/api/v1${path}`,
          {
            signal: controller.signal,
            ...(command
              ? {
                  method: command.method,
                  headers: {
                    'Content-Type': 'application/json',
                    'X-Command-Id': command.id,
                  },
                  body: JSON.stringify(command.data),
                }
              : {}),
          },
        );
        const data = await response.json();
        if (!response.ok)
          throw Error(data.message ?? `HTTP ${response.status}`);
        return data;
      } finally {
        clearTimeout(timer);
      }
    },
    [baseUrl],
  );
  const refresh = useCallback(async () => {
    try {
      setSettings(await request('/settings'));
      setError('');
    } catch (e) {
      setSettings(null);
      setError(e instanceof Error ? e.message : 'Serveur indisponible');
    }
  }, [request]);
  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 5000);
    return () => clearInterval(timer);
  }, [refresh]);
  const execute = async (command: Pending) => {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setPending(command);
    setError('');
    try {
      await request(command.path, command);
      setPending(null);
      await refresh();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Réglage non confirmé');
    } finally {
      setBusy(false);
      running.current = false;
    }
  };
  const send = (path: string, data: object, method = 'POST') =>
    execute({ path, data, method, id: operationUUID() });
  const selected = settings?.scales.find(s => s.source === source);
  const detected =
    selected?.detected && Date.now() - Date.parse(selected.observedAt) <= 10000;
  return (
    <Card>
      <Title>Paramètres du serveur</Title>
      <Message text="Ces réglages sont partagés par tous les téléphones et conservés par le serveur. Une panne ne change jamais le mode." />
      {!!error && <Message error text={error} />}
      {settings && (
        <>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
            <Text>Mode sans capteurs</Text>
            <Switch
              accessibilityLabel="Mode sans capteurs"
              value={settings.withoutSensors}
              disabled={busy || !!pending}
              onValueChange={value =>
                send('/settings/sensors', { withoutSensors: value }, 'PUT')
              }
            />
          </View>
          <Message
            text={
              settings.withoutSensors
                ? 'Sans capteurs : les scans utilisent le parcours validé, sans poids inventé. Ce choix explicite reste actif après redémarrage.'
                : 'Avec capteurs : le scan prépare uniquement l’opération. Seul le poids matériel calibré et corrélé peut confirmer le mouvement. Aucun repli automatique.'
            }
          />
          {!settings.persistent && (
            <Message text="Profil initial du lancement ; choisissez explicitement un mode pour le mémoriser." />
          )}
          <Title>Calibration de la balance</Title>
          <Message text="Trois mesures raw reçues depuis moins de dix secondes sont nécessaires. Videz le plateau, capturez la tare, puis posez une masse connue et attendez trois nouvelles mesures stables avant confirmation. Aucun stock n’est modifié." />
          {settings.scales.length === 0 && (
            <Message text="Aucune balance détectée. La calibration devient disponible dès réception de mesures brutes." />
          )}
          {settings.scales.map(scale => (
            <Action
              key={scale.source}
              secondary
              label={`${scale.source} · ${
                scale.detected ? 'détectée' : 'mesures périmées'
              } · raw ${scale.raw} (${scale.samples} mesures)`}
              disabled={busy || !!pending || !scale.detected}
              onPress={() => setSource(scale.source)}
            />
          ))}
          {!!source && <Message text={`Balance sélectionnée : ${source}`} />}
          <Action
            label="Plateau vide : capturer la tare"
            disabled={!detected || busy || !!pending}
            onPress={() => send('/calibration/tare', { source })}
          />
          {settings.tare && (
            <Message
              text={`Tare capturée : ${settings.tare.source} · raw ${settings.tare.raw}`}
            />
          )}
          <Field
            label="Masse de référence réellement posée (grammes)"
            value={mass}
            onChangeText={setMass}
            numeric
          />
          <Action
            label="Confirmer la masse connue et calibrer"
            disabled={
              !detected ||
              busy ||
              !!pending ||
              settings.tare?.source !== source ||
              !(Number(mass.replace(',', '.')) > 0)
            }
            onPress={() =>
              send('/calibration/confirm', {
                source,
                referenceGrams: Number(mass.replace(',', '.')),
                tareId: settings.tare?.id,
              })
            }
          />
          {settings.calibration && (
            <Message
              text={`Calibration enregistrée : ${settings.calibration.source}, masse de référence ${settings.calibration.referenceGrams} g, ${settings.calibration.measuredAt}. La validation physique reste nécessaire ; les anciens messages raw sans opération partagée ne déclenchent aucun mouvement.`}
            />
          )}
        </>
      )}
      {pending && (
        <Action
          label="Réessayer le même réglage"
          disabled={busy}
          onPress={() => execute(pending)}
        />
      )}
      <Action
        secondary
        label="Actualiser les paramètres"
        disabled={busy}
        onPress={refresh}
      />
    </Card>
  );
}
