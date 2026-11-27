import React, { useEffect, useState } from 'react';
import {
  Image,
  Modal,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from './ui/SafeArea';
import { Settings, RefreshCw, BarChart3, ShoppingCart } from './ui/icons';
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
import { ConnectionProfiles } from './ui/ConnectionProfiles';
import connectionStorage from './data/connectionStorage';
import { ServerSettingsPanel } from './ui/ServerSettingsPanel';
import { ScannerScreen } from './scanner/ScannerScreen';
import { AnalysisScreen, GradientSurface } from './ui/AnalysisScreen';
import {
  loadConnection,
  normalizeServer,
  saveConnection,
} from './data/connection';
const c = theme.colors;
export default function SmartStockApp({
  repository = demoRepository,
}: {
  repository?: StockRepository;
}) {
  const [graphProduct, setGraphProduct] = useState('');
  const [scannerOpen, setScannerOpen] = useState(false);
  const [returnScreen, setReturnScreen] =
    useState<Destination>('Tableau de bord');
  const openSuggestions = () => {
    setReturnScreen(screen);
    setScreen('Suggestions');
  };
  const [override, setOverride] = useState<StockRepository | null>(null);
  const [restoring, setRestoring] = useState(true);
  const active = override ?? repository;
  const data = useInventory(active, !restoring);
  const [lastServer, setLastServer] = useState('');
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
  useEffect(() => {
    let mounted = true;
    loadConnection(Platform.OS === 'web')
      .then(saved => {
        if (!mounted || !saved) return;
        setUrl(saved.url);
        setLastServer(saved.url);
        setOverride(
          saved.mode === 'api'
            ? new ApiStockRepository(saved.url)
            : demoRepository,
        );
      })
      .catch(() => {
        if (mounted) {
          setOverride({
            mode: 'api',
            load: async () => {
              throw Error(
                'Connexion enregistrée illisible. Configurez le serveur dans Connexion.',
              );
            },
          });
          setConfigError(
            'Impossible de restaurer la connexion. Aucun remplacement par des données fictives.',
          );
          setScreen('Connexion');
        }
      })
      .finally(() => {
        if (mounted) setRestoring(false);
      });
    return () => {
      mounted = false;
    };
  }, []);
  const connect = async () => {
    try {
      const address = normalizeServer(url, Platform.OS === 'web');
      const pending = JSON.parse(
        (await connectionStorage.getItem('native-scanner-pending-v1')) ??
          'null',
      );
      if (pending && pending.baseUrl !== address)
        throw Error(
          `Opération en attente sur ${pending.baseUrl} : la résoudre dans Scanner avant de changer de serveur.`,
        );
      await saveConnection({ mode: 'api', url: address });
      setUrl(address);
      setLastServer(address);
      setOverride(new ApiStockRepository(address));
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
  if (restoring)
    return (
      <SafeAreaProvider>
        <SafeAreaView style={{ flex: 1, backgroundColor: c.background }}>
          <Loading />
        </SafeAreaView>
      </SafeAreaProvider>
    );
  return (
    <SafeAreaProvider>
      <SafeAreaView style={{ flex: 1, backgroundColor: c.background }}>
        <View style={{ flex: 1, flexDirection: 'row' }}>
          {wide && (
            <Navigation
              wide
              active={screen}
              onSelect={navigate}
              onScan={() => setScannerOpen(true)}
            />
          )}
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
                      <GradientSurface>
                        <Text
                          style={{
                            color: 'white',
                            fontSize: 20,
                            fontWeight: '700',
                          }}
                        >
                          Anticiper les besoins
                        </Text>
                        <Action
                          secondary
                          label="Graphiques"
                          icon={<BarChart3 color={c.navigation} size={20} />}
                          onPress={() => {
                            setGraphProduct('');
                            navigate('Graphiques');
                          }}
                        />
                        <Action
                          secondary
                          label="Suggestions de réapprovisionnement"
                          icon={<ShoppingCart color={c.navigation} size={20} />}
                          onPress={openSuggestions}
                        />
                      </GradientSurface>
                    )}
                    {(screen === 'Stock' || screen === 'Produit') && (
                      <View style={{ gap: 10 }}>
                        <Action
                          secondary
                          label="Graphiques"
                          icon={<BarChart3 color={c.navigation} size={20} />}
                          onPress={() => {
                            setGraphProduct(
                              screen === 'Produit'
                                ? data.snapshot?.batches.find(
                                    b => b.id === batchId,
                                  )?.productId ?? ''
                                : '',
                            );
                            navigate('Graphiques');
                          }}
                        />
                        <Action
                          secondary
                          label="Suggestions de réapprovisionnement"
                          onPress={openSuggestions}
                        />
                      </View>
                    )}
                    {screen === 'Graphiques' && (
                      <AnalysisScreen
                        repository={active}
                        initialProduct={graphProduct}
                      />
                    )}
                    {screen === 'Suggestions' && !wide && (
                      <>
                        <Action
                          secondary
                          label="Fermer les suggestions"
                          onPress={() => navigate(returnScreen)}
                        />
                        <AnalysisScreen repository={active} suggestions />
                      </>
                    )}
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
                  <ConnectionProfiles
                    activeUrl={lastServer}
                    select={address => {
                      setUrl(address);
                      setLastServer(address);
                      setOverride(new ApiStockRepository(address));
                    }}
                  />
                )}
                {screen === 'Connexion' && !demo && (
                  <ServerSettingsPanel
                    baseUrl={lastServer}
                    onChanged={data.refresh}
                  />
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
                        onPress={async () => {
                          try {
                            await saveConnection({
                              mode: 'demo',
                              url: lastServer,
                            });
                            setOverride(demoRepository);
                            setEmpty(false);
                            setDemoLoading(false);
                            setConfigError('');
                          } catch {
                            setConfigError(
                              'Impossible de mémoriser le mode Démo',
                            );
                          }
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
          <Navigation
            wide={false}
            active={screen}
            onSelect={navigate}
            onScan={() => setScannerOpen(true)}
          />
        )}
        <Modal
          visible={scannerOpen}
          animationType="slide"
          onRequestClose={() => setScannerOpen(false)}
        >
          <SafeAreaView style={{ flex: 1, backgroundColor: c.background }}>
            <ScannerScreen
              active={scannerOpen}
              baseUrl={demo ? '' : lastServer}
              onClose={() => setScannerOpen(false)}
              onConfirmed={data.refresh}
            />
          </SafeAreaView>
        </Modal>
        <Modal
          visible={screen === 'Suggestions' && wide}
          transparent
          animationType="fade"
          onRequestClose={() => navigate(returnScreen)}
        >
          <View
            style={{
              flex: 1,
              backgroundColor: 'rgba(7,30,48,0.5)',
              alignItems: 'center',
              justifyContent: 'center',
              padding: 16,
            }}
          >
            <View
              accessibilityViewIsModal
              style={{
                width: Math.min(width - 32, 760),
                maxHeight: '85%',
                backgroundColor: c.background,
                borderRadius: 16,
                padding: 20,
                gap: 16,
              }}
            >
              <Title>Suggestions de réapprovisionnement</Title>
              <Action
                secondary
                label="Fermer les suggestions"
                onPress={() => navigate(returnScreen)}
              />
              <ScrollView contentContainerStyle={{ gap: 16 }}>
                <AnalysisScreen repository={active} suggestions />
              </ScrollView>
            </View>
          </View>
        </Modal>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
