import React, { useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import { Action, Card, Field, Message, Title } from '../ui/components';
import storage from '../data/connectionStorage';
import { CameraPreview } from './CameraPreview';
import { operationUUID } from './identifiers';
import { ScanSession, ScanState } from './ScanSession';
import { playReadTone } from './feedback';

export function ScannerScreen({
  active,
  baseUrl,
  onClose,
  onConfirmed,
}: {
  active: boolean;
  baseUrl: string;
  onClose: () => void;
  onConfirmed: () => Promise<void>;
}) {
  const [view, setView] = useState<ScanState>({
    phase: 'ready',
    message: 'Centrez un seul DataMatrix.',
  });
  const [restored, setRestored] = useState(false);
  const [name, setName] = useState('');
  const [soundEnabled, setSoundEnabled] = useState(true);
  const [soundError, setSoundError] = useState('');
  const sound = useRef(true);
  sound.current = soundEnabled;
  const confirmed = useRef(onConfirmed);
  confirmed.current = onConfirmed;
  const session = useRef<ScanSession | null>(null);
  if (!session.current)
    session.current = new ScanSession(
      storage,
      operationUUID,
      setView,
      () => confirmed.current(),
      undefined,
      () => {
        if (sound.current) playReadTone();
      },
    );
  useEffect(() => {
    Promise.all([
      session.current!.restore(),
      storage
        .getItem('scanner-sound-enabled')
        .then(value => setSoundEnabled(value !== 'false')),
    ])
      .then(() => setRestored(true))
      .catch(() =>
        setView({
          phase: 'error',
          uncertain: true,
          message:
            'Impossible de relire l’opération locale. Aucun nouveau scan autorisé pour éviter un doublon.',
        }),
      );
  }, []);
  const reset = () => {
    session.current!.reset().catch(() =>
      setView(v => ({
        ...v,
        message: 'Impossible de sauvegarder le nouvel armement.',
        uncertain: true,
      })),
    );
  };
  const pending = view.pending;
  return (
    <ScrollView
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
    >
      <View style={styles.header}>
        <Title>Scanner</Title>
        <Action secondary label="Fermer le scanner" onPress={onClose} />
      </View>
      {restored && view.phase === 'ready' && active && (
        <CameraPreview
          active={active}
          onCodes={values => {
            session.current!.detect(values, baseUrl);
          }}
        />
      )}
      <Message error={view.phase === 'error'} text={view.message} />
      <View style={styles.header}>
        <Text>Son à la lecture</Text>
        <Switch
          accessibilityLabel="Son à la lecture"
          value={soundEnabled}
          onValueChange={value => {
            sound.current = value;
            setSoundEnabled(value);
            storage
              .setItem('scanner-sound-enabled', String(value))
              .catch(() =>
                setSoundError(
                  'Le choix sonore vaut pour cette session, mais n’a pas pu être mémorisé.',
                ),
              );
          }}
        />
      </View>
      <Message text="Un bip confirme uniquement la lecture du DataMatrix, jamais la réussite d’un mouvement." />
      {!!soundError && <Message error text={soundError} />}
      {pending && (
        <Card>
          <Text>Produit : {view.product ?? 'Vérification en cours'}</Text>
          <Text>GTIN : {pending.code.gtin ?? 'Absent'}</Text>
          <Text>Lot : {pending.code.lot ?? 'Absent'}</Text>
          <Text>Péremption : {pending.code.expiration ?? 'Absente'}</Text>
          <Text>
            Série :{' '}
            {pending.code.serial ??
              'Absente — boîte non identifiable individuellement'}
          </Text>
          <Text selectable>
            Valeur brute :{' '}
            {pending.code.raw.split(String.fromCharCode(29)).join('␝')}
          </Text>
          <Text selectable>Opération : {pending.operationId}</Text>
          <Message text={`Serveur : ${pending.baseUrl}`} />
          <Message
            text={
              pending.mode === 'test'
                ? 'TEST SANS BALANCE — aucun poids fabriqué.'
                : pending.mode === 'normal'
                ? 'Mode normal : validation scan/poids par le moteur Scala/Akka.'
                : 'Mode déterminé auprès du backend.'
            }
          />
        </Card>
      )}
      {view.phase === 'working' && (
        <Message text="Traitement verrouillé : les images suivantes ne créent pas d’opération." />
      )}
      {view.issue === 'unknown_product' && !view.uncertain && (
        <Card>
          <Title>Enregistrer ce produit</Title>
          <Message
            text={`GTIN prérempli : ${pending?.code.gtin}. Confirmez le lot, la péremption et la série affichés ci-dessus.`}
          />
          <Field
            label="Nom réel du produit"
            value={name}
            onChangeText={setName}
          />
          <Message text="Masse non renseignée. Aucune présence initiale ni quantité ne sera inventée. Le lot sera créé à zéro." />
          <Action
            label="Enregistrer ce produit"
            disabled={view.phase === 'working' || !name.trim()}
            onPress={() => {
              session.current!.register(name);
            }}
          />
        </Card>
      )}
      {view.issue === 'new_batch' && !view.uncertain && (
        <Card>
          <Message
            text={`Produit reconnu : ${view.product}. Enregistrer ce nouveau lot à zéro, sans créer une seconde fiche produit.`}
          />
          <Action
            label="Enregistrer ce lot"
            disabled={view.phase === 'working'}
            onPress={() => {
              session.current!.register();
            }}
          />
        </Card>
      )}
      {(view.phase === 'error' || view.phase === 'waiting') && pending && (
        <Action
          label={
            view.phase === 'waiting'
              ? 'Vérifier la confirmation'
              : 'Réessayer la même opération'
          }
          onPress={() => {
            session.current!.submit();
          }}
        />
      )}
      {view.choice && !view.uncertain && pending?.mode === 'test' && (
        <Card>
          <Message text="La présence individuelle n’est pas connue. Choisissez explicitement une action ; le backend conserve toutes ses restrictions." />
          <Action
            label="Confirmer un ajout"
            onPress={() => {
              session.current!.submit('entry');
            }}
          />
          <Action
            secondary
            label="Confirmer un retrait"
            onPress={() => {
              session.current!.submit('exit');
            }}
          />
        </Card>
      )}
      {view.phase === 'waiting' && (
        <Message text="Un nouveau scan sera une autre opération. Le scan précédent reste reçu côté serveur ; ceci ne l’annule pas. Ne réarmez pas pour un simple retry réseau." />
      )}
      {view.phase !== 'ready' && (
        <Action
          label="Scanner à nouveau"
          disabled={view.phase === 'working' || !!view.uncertain}
          onPress={reset}
        />
      )}
      {view.uncertain && (
        <Message text="Résultat à réconcilier : conservez le même identifiant et utilisez Réessayer. La fermeture conserve cette opération." />
      )}
    </ScrollView>
  );
}
const styles = StyleSheet.create({
  content: { padding: 18, gap: 14 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
  },
});
