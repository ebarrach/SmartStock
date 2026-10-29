import React, { useState } from 'react';
import { Button, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

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
const products: readonly Product[] = [
  {
    id: 'compresses-armoire',
    name: 'Compresses',
    place: 'Armoire',
    quantity: 40,
    batch: 'LOT-C',
    expires: '2027-06-30',
  },
  {
    id: 'compresses-sac',
    name: 'Compresses',
    place: 'Sac',
    quantity: 4,
    batch: 'LOT-S',
    expires: '2027-06-30',
  },
];

export default function App() {
  const [screen, setScreen] = useState<Screen>('Stock');
  const [place, setPlace] = useState<Place>('Armoire');
  const [selected, setSelected] = useState<Product | undefined>();
  const [offline, setOffline] = useState(false);
  const [empty, setEmpty] = useState(false);
  const [loading, setLoading] = useState(false);
  const visible = empty ? [] : products.filter(item => item.place === place);
  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.root}>
        <ScrollView contentContainerStyle={styles.content}>
          <Text style={styles.title}>SmartStock</Text>
          <Text>Maquette interactive — données simulées</Text>
          <Text accessibilityRole="alert">
            {offline
              ? 'Hors connexion — données de démonstration non actualisées'
              : 'Connexion simulée — aucun appareil connecté'}
          </Text>
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
              <Text>Température : 20 °C · Humidité : 50 % (simulation)</Text>
              {loading ? (
                <Text>Chargement simulé…</Text>
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
                        setSelected(item);
                        setScreen('Produit');
                      }}
                    />
                  </View>
                ))
              )}
              <Button
                title={
                  loading
                    ? 'Terminer le chargement simulé'
                    : 'Simuler un chargement'
                }
                disabled={offline}
                onPress={() => setLoading(!loading)}
              />
              <Button
                title={
                  empty
                    ? 'Restaurer les données de démonstration'
                    : 'Simuler un stock vide'
                }
                onPress={() => setEmpty(!empty)}
              />
            </>
          )}
          {screen === 'Produit' && selected && (
            <>
              <Text>{selected.name}</Text>
              <Text>Stock : {selected.quantity}</Text>
              <Text>Lot : {selected.batch}</Text>
              <Text>Péremption : {selected.expires}</Text>
              <Text>Identification : DataMatrix (exemple non scanné)</Text>
              <Button
                title="Retour au stock"
                onPress={() => setScreen('Stock')}
              />
            </>
          )}
          {screen === 'Mouvements' && (
            <>
              <Text>Exemple : retrait d’une boîte de compresses, 40 → 39.</Text>
              <Text>
                Historique fictif, sans modification du stock affiché.
              </Text>
            </>
          )}
          {screen === 'Alertes' && (
            <>
              <Text>Exemple : stock du sac faible (4 boîtes).</Text>
              <Text>
                Seuil de démonstration : 5. Aucune notification réelle.
              </Text>
            </>
          )}
          {screen === 'Connexion' && (
            <>
              <Text>Armoire : Wi-Fi · Sac : Bluetooth/BLE</Text>
              <Text>Mode actuel : simulation locale.</Text>
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
