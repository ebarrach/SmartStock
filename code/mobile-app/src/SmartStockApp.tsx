import React, { useState } from 'react';
import {
  Image,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from './ui/SafeArea';
import { Settings, RefreshCw } from './ui/icons';
import {
  ApiStockRepository,
  demoRepository,
  StockRepository,
} from './data/repository';
import { useInventory } from './data/useInventory';
import { Action, Card, Field, Loading, Message, Title } from './ui/components';
import { Destination, Navigation } from './ui/Navigation';
import {
  ActivityScreen,
  Dashboard,
  DetailScreen,
  StockScreen,
} from './ui/InventoryScreens';
import { layoutForWidth, theme } from './ui/theme';
import logo from './assets/logo';
const c = theme.colors;
export default function SmartStockApp({
  repository = demoRepository,
}: {
  repository?: StockRepository;
}) {
  const [override, setOverride] = useState<StockRepository | null>(null);
  const active = override ?? repository;
  const data = useInventory(active);
  const [screen, setScreen] = useState<Destination>('Tableau de bord'),
    [batchId, setBatchId] = useState(''),
    [url, setUrl] = useState(''),
    [configError, setConfigError] = useState(''),
    [empty, setEmpty] = useState(false),
    [demoLoading, setDemoLoading] = useState(false);
  const { width } = useWindowDimensions();
  const layout = layoutForWidth(width),
    wide = layout.navigation === 'sidebar',
    demo = active.mode === 'simulated';
  const navigate = (destination: Destination) => setScreen(destination);
  const connect = () => {
    try {
      if (
        !/^https?:\/\//i.test(url.trim()) &&
        !(Platform.OS === 'web' && url.trim() === '/agent')
      ) {
        throw Error(
          'Indiquez une URL HTTP/HTTPS complète ou /agent sur le navigateur.',
        );
      }
      const parsed = new URL(
        url.trim(),
        Platform.OS === 'web' ? 'http://localhost' : undefined,
      );
      if (
        !['http:', 'https:'].includes(parsed.protocol) ||
        parsed.username ||
        parsed.password
      )
        throw Error('URL HTTP ou HTTPS sans identifiants requise');
      setOverride(new ApiStockRepository(url.trim().replace(/\/$/, '')));
      setConfigError('');
      setEmpty(false);
      setDemoLoading(false);
    } catch (e) {
      setConfigError(e instanceof Error ? e.message : 'Adresse invalide');
    }
  };
  const status = data.error
    ? 'Hors connexion — données non actualisées'
    : data.loading && !data.snapshot
    ? 'Connexion en cours…'
    : demo
    ? 'Données simulées'
    : data.synced
    ? 'API connectée'
    : 'Non connecté';
  return (
    <SafeAreaProvider>
      <SafeAreaView style={{ flex: 1, backgroundColor: c.background }}>
        <View style={{ flex: 1, flexDirection: 'row' }}>
          {wide && <Navigation wide active={screen} onSelect={navigate} />}
          <View style={{ flex: 1, minWidth: 0 }}>
            <View
              style={{
                backgroundColor: 'white',
                paddingHorizontal: layout.horizontalPadding,
                paddingVertical: 12,
                borderBottomWidth: 1,
                borderColor: c.border,
                flexDirection: 'row',
                alignItems: 'center',
                gap: 10,
              }}
            >
              <Image
                source={logo}
                accessibilityLabel="SmartStock"
                style={{ width: 40, height: 40 }}
                resizeMode="contain"
              />
              <View style={{ flex: 1 }}>
                <Text
                  numberOfLines={1}
                  style={{
                    color: c.navigation,
                    fontWeight: '800',
                    fontSize: width < 360 ? 18 : 22,
                    fontFamily: Platform.OS === 'ios' ? 'System' : 'sans-serif',
                  }}
                >
                  SmartStock
                </Text>
                <Text style={{ color: c.muted, fontSize: 12 }}>
                  Armoire médicale connectée
                </Text>
              </View>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Actualiser"
                disabled={data.loading}
                onPress={data.refresh}
                style={{ padding: 12, minHeight: 48, minWidth: 48 }}
              >
                <RefreshCw color={c.navigation} size={22} />
              </Pressable>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Connexion et paramètres"
                onPress={() => navigate('Connexion')}
                style={{ padding: 12, minHeight: 48, minWidth: 48 }}
              >
                <Settings color={c.navigation} size={22} />
              </Pressable>
            </View>
            <KeyboardAvoidingView
              style={{ flex: 1 }}
              behavior={Platform.OS === 'ios' ? 'padding' : undefined}
            >
              <ScrollView
                keyboardShouldPersistTaps="handled"
                contentContainerStyle={{
                  padding: layout.horizontalPadding,
                  gap: 18,
                  paddingBottom: 28,
                }}
              >
                <View style={{ gap: 6 }}>
                  <Title>{screen}</Title>
                  <Message text={status} error={!!data.error} />
                  {!demo && data.snapshot?.simulated && (
                    <Message text="Jeu de test signalé par le serveur API." />
                  )}
                  {data.synced && (
                    <Message
                      text={`Dernière synchronisation : ${data.synced.toLocaleTimeString()}`}
                    />
                  )}
                </View>
                {data.error && (
                  <Card>
                    <Message error text={data.error} />
                    <Action label="Réessayer" onPress={data.refresh} />
                  </Card>
                )}
                {!!configError && <Message error text={configError} />}
                {(data.loading && !data.snapshot) || demoLoading ? (
                  <Loading />
                ) : (
                  <>
                    {screen === 'Tableau de bord' && (
                      <Dashboard
                        snapshot={data.snapshot}
                        columns={layout.columns}
                      />
                    )}
                    {screen === 'Stock' && (
                      <StockScreen
                        snapshot={data.snapshot}
                        empty={demo && empty}
                        wide={wide}
                        onDetail={b => {
                          setBatchId(b.id);
                          navigate('Produit');
                        }}
                      />
                    )}
                    {screen === 'Produit' && (
                      <DetailScreen
                        snapshot={data.snapshot}
                        batchId={batchId}
                        onBack={() => navigate('Stock')}
                      />
                    )}
                    {(screen === 'Mouvements' || screen === 'Alertes') && (
                      <ActivityScreen
                        snapshot={data.snapshot}
                        alerts={screen === 'Alertes'}
                      />
                    )}
                  </>
                )}
                {screen === 'Connexion' && (
                  <Card>
                    <Title>Connexion à l’armoire</Title>
                    <Message text="API locale via Wi-Fi. Le mode API ne bascule jamais automatiquement vers des données de démonstration." />
                    <Field
                      label="Adresse de l’API"
                      value={url}
                      onChangeText={setUrl}
                    />
                    <Action label="Connecter l’API" onPress={connect} />
                    <Action
                      secondary
                      label="Actualiser la connexion"
                      onPress={data.refresh}
                    />
                    <Message text="Sur émulateur Android : http://10.0.2.2:8081. Sur navigateur : /agent ou une origine autorisée par votre serveur." />
                    <Action
                      secondary
                      label="Ouvrir la démonstration"
                      onPress={() => navigate('Démonstration')}
                    />
                  </Card>
                )}
                {screen === 'Démonstration' && (
                  <Card>
                    <Title>Espace Démonstration</Title>
                    <Message text="Ces commandes ne modifient jamais le stock réel." />
                    {!demo ? (
                      <Action
                        label="Activer le mode démo"
                        onPress={() => {
                          setOverride(demoRepository);
                          setEmpty(false);
                          setDemoLoading(false);
                        }}
                      />
                    ) : (
                      <>
                        <Message text="Données simulées" />
                        <Action
                          secondary
                          label={
                            empty
                              ? 'Restaurer les données de démonstration'
                              : 'Simuler un stock vide'
                          }
                          onPress={() => {
                            setEmpty(!empty);
                            navigate('Stock');
                          }}
                        />
                        <Action
                          secondary
                          label={
                            demoLoading
                              ? 'Terminer le chargement simulé'
                              : 'Simuler un chargement'
                          }
                          onPress={() => setDemoLoading(!demoLoading)}
                        />
                      </>
                    )}
                  </Card>
                )}
              </ScrollView>
            </KeyboardAvoidingView>
          </View>
        </View>
        {!wide && (
          <Navigation wide={false} active={screen} onSelect={navigate} />
        )}
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
