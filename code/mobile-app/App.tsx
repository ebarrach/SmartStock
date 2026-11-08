import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Button, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

import {
  demoRepository,
  Snapshot,
  StockRepository,
} from './src/data/repository';

type Place = 'Armoire' | 'Sac';
type Screen = 'Stock' | 'Produit' | 'Mouvements' | 'Alertes' | 'Connexion';
type Product = Readonly<{
  id: string;
  name: string;
  place: Place;
  quantity: number;
  batch: string;
  expires: string;
}>;
export default function App({
  repository = demoRepository,
}: {
  repository?: StockRepository;
}) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const request = useRef(0);
  const refresh = useCallback(async () => {
    const current = ++request.current;
    setLoading(true);
    try {
      const data = await repository.load();
      if (current === request.current) {
        setSnapshot(data);
        setError(null);
      }
    } catch (cause) {
      if (current === request.current) {
        setError(
          cause instanceof Error ? cause.message : 'Service indisponible',
        );
      }
    } finally {
      if (current === request.current) {
        setLoading(false);
      }
    }
  }, [repository]);
  const invalidate = useCallback(() => {
    request.current += 1;
  }, []);
  useEffect(() => {
    refresh();
    const interval =
      repository.mode === 'api' ? setInterval(refresh, 5000) : undefined;
    return () => {
      if (interval) {
        clearInterval(interval);
      }
      invalidate();
    };
  }, [refresh, invalidate, repository.mode]);
  const products: readonly Product[] = (snapshot?.batches ?? []).map(batch => ({
    id: batch.id,
    name:
      snapshot?.products.find(product => product.id === batch.productId)
        ?.name ?? batch.productId,
    place: batch.location === 'cabinet' ? 'Armoire' : 'Sac',
    quantity: batch.quantity,
    batch: batch.id,
    expires: batch.expiresOn,
  }));
  const [screen, setScreen] = useState<Screen>('Stock');
  const [place, setPlace] = useState<Place>('Armoire');
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const selected = products.find(item => item.id === selectedId);
  const [offline, setOffline] = useState(false);
  const [empty, setEmpty] = useState(false);
  const [loading, setLoading] = useState(false);
  const visible = empty ? [] : products.filter(item => item.place === place);
  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.root}>
        <ScrollView contentContainerStyle={styles.content}>
          <Text style={styles.title}>SmartStock</Text>
          <Text>
            {repository.mode === 'simulated' || snapshot?.simulated
              ? 'Maquette interactive — données simulées'
              : 'SmartStock — données API'}
          </Text>
          <Text accessibilityRole="alert">
            {offline || error
              ? 'Hors connexion — données non actualisées'
              : repository.mode === 'simulated'
              ? 'Connexion simulée — aucun appareil connecté'
              : snapshot
              ? 'API connectée'
              : 'Connexion à l’API…'}
          </Text>
          <Text accessibilityRole="alert">
            {error ? `Erreur de chargement : ${error}` : ''}
          </Text>
          {error && (
            <Button
              title="Réessayer"
              onPress={() => {
                refresh();
              }}
            />
          )}
          <View accessibilityLabel="Navigation">
            {(['Stock', 'Mouvements', 'Alertes', 'Connexion'] as const).map(
              item => (
                <Button
                  key={item}
                  title={item}
                  onPress={() => setScreen(item)}
                />
              ),
            )}
          </View>
          <Text accessibilityRole="header" style={styles.heading}>
            {screen}
          </Text>
          {screen === 'Stock' && (
            <>
              <Button
                title={`Afficher ${
                  place === 'Armoire' ? 'le sac' : "l'armoire"
                }`}
                onPress={() =>
                  setPlace(place === 'Armoire' ? 'Sac' : 'Armoire')
                }
              />
              <Text>Emplacement : {place}</Text>
              <Text>
                Température :{' '}
                {snapshot?.readings
                  .filter(r => r.kind === 'temperature.measured')
                  .at(-1)?.value ?? '—'}{' '}
                °C · Humidité :{' '}
                {snapshot?.readings
                  .filter(r => r.kind === 'humidity.measured')
                  .at(-1)?.value ?? '—'}{' '}
                %
              </Text>
              <Button
                title="Actualiser"
                onPress={() => {
                  refresh();
                }}
                disabled={offline || loading}
              />
              {loading ? (
                <Text>
                  {repository.mode === 'simulated'
                    ? 'Chargement simulé…'
                    : 'Chargement…'}
                </Text>
              ) : visible.length === 0 ? (
                <Text>Aucun produit dans cet emplacement.</Text>
              ) : (
                visible.map(item => (
                  <View key={item.id} style={styles.card}>
                    <Text>
                      {item.name} : {item.quantity}
                    </Text>
                    <Button
                      title={`Détail ${item.name}`}
                      onPress={() => {
                        setSelectedId(item.id);
                        setScreen('Produit');
                      }}
                    />
                  </View>
                ))
              )}
              {repository.mode === 'simulated' && (
                <Button
                  title={
                    loading
                      ? 'Terminer le chargement simulé'
                      : 'Simuler un chargement'
                  }
                  disabled={offline}
                  onPress={() => setLoading(!loading)}
                />
              )}
              {repository.mode === 'simulated' && (
                <Button
                  title={
                    empty
                      ? 'Restaurer les données de démonstration'
                      : 'Simuler un stock vide'
                  }
                  onPress={() => setEmpty(!empty)}
                />
              )}
            </>
          )}
          {screen === 'Produit' && selected && (
            <>
              <Text>{selected.name}</Text>
              <Text>Stock : {selected.quantity}</Text>
              <Text>Lot : {selected.batch}</Text>
              <Text>Péremption : {selected.expires}</Text>
              <Text>
                Identification : DataMatrix
                {repository.mode === 'simulated' ? ' (exemple non scanné)' : ''}
              </Text>
              <Button
                title="Retour au stock"
                onPress={() => setScreen('Stock')}
              />
            </>
          )}
          {screen === 'Mouvements' && (
            <>
              <Text>Mouvements : {snapshot?.movements.length ?? 0}</Text>
              {snapshot?.movements.map(m => (
                <Text key={m.id}>
                  {m.batchId} : {m.delta} → {m.quantityAfter} ({m.occurredAt})
                </Text>
              ))}
              <Text>
                {repository.mode === 'simulated'
                  ? 'Historique fictif, sans modification du stock affiché.'
                  : 'Historique reçu du service SmartStock.'}
              </Text>
            </>
          )}
          {screen === 'Alertes' && (
            <>
              {snapshot?.alerts.map((a, i) => (
                <Text key={`${a.subjectId}-${i}`}>{a.message}</Text>
              ))}
              {snapshot?.alerts.length === 0 && <Text>Aucune alerte.</Text>}
              <Text>
                {repository.mode === 'simulated'
                  ? 'Seuil de démonstration : 5. Aucune notification réelle.'
                  : 'Alertes calculées par SmartStock.'}
              </Text>
            </>
          )}
          {screen === 'Connexion' && (
            <>
              <Text>Armoire : Wi-Fi · Sac : Bluetooth/BLE</Text>
              <Text>
                Mode actuel :{' '}
                {repository.mode === 'simulated' ? 'simulation locale' : 'API'}.
              </Text>
              {repository.mode === 'simulated' && (
                <Button
                  title={
                    offline
                      ? 'Simuler une reconnexion'
                      : 'Simuler une déconnexion'
                  }
                  onPress={() => {
                    setOffline(!offline);
                    setLoading(false);
                  }}
                />
              )}
            </>
          )}
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#f3f6f8' },
  content: { padding: 20, gap: 14 },
  title: { fontSize: 28, fontWeight: '700', color: '#164a5b' },
  heading: { fontSize: 22, fontWeight: '600' },
  card: { padding: 16, backgroundColor: '#fff', borderRadius: 8, gap: 8 },
});
