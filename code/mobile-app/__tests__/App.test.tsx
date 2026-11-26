import React from 'react';
import renderer, { act, ReactTestRenderer } from 'react-test-renderer';
import { Text, TextInput } from 'react-native';
import App from '../App';
import { MockStockRepository, StockRepository } from '../src/data/repository';
import { layoutForWidth } from '../src/ui/theme';
import { ActivityScreen, cabinetData } from '../src/ui/InventoryScreens';
import { saveConnection } from '../src/data/connection';
jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
);
jest.mock('../src/ui/svg', () => {
  const { View } = require('react-native');
  return {
    __esModule: true,
    default: View,
    Rect: View,
    Defs: View,
    LinearGradient: View,
    Stop: View,
  };
});
jest.mock('../src/ui/icons', () =>
  Object.fromEntries(
    [
      'LayoutDashboard',
      'Package',
      'ArrowLeftRight',
      'Bell',
      'Thermometer',
      'Droplets',
      'Fan',
      'Settings',
      'RefreshCw',
      'BarChart3',
      'ShoppingCart',
    ].map(name => [name, () => null]),
  ),
);
let tree: ReactTestRenderer;
beforeEach(async () => {
  await act(async () => {
    tree = renderer.create(<App />);
  });
});
afterEach(async () => {
  await act(async () => tree.unmount());
});
async function press(label: string) {
  const node = tree.root.findAll(
    n =>
      n.props.accessibilityLabel === label &&
      typeof n.props.onPress === 'function',
  )[0];
  if (!node) throw Error('Bouton absent : ' + label);
  await act(async () => node.props.onPress());
}
function text() {
  return JSON.stringify(tree.toJSON());
}

test('affiche la serie et la presence initiale inconnue dans les mouvements', async () => {
  const snapshot = await new MockStockRepository().load();
  await act(async () => {
    tree.unmount();
    tree = renderer.create(
      <ActivityScreen
        alerts={false}
        snapshot={{
          ...snapshot,
          movements: [
            {
              id: 'serial-movement',
              batchId: 'LOT-C',
              delta: -1,
              quantityAfter: 39,
              occurredAt: '2026-11-26T10:00:00Z',
              eventId: 'serial-event',
              serial: 'UNIT-1',
              initialPresenceUnknown: true,
            },
          ],
        }}
      />,
    );
  });
  expect(text()).toContain('UNIT-1');
  expect(text()).toContain('Présence initiale inconnue');
});

test('restaure une connexion API hors ligne après remontage sans afficher la Démo', async () => {
  await act(async () => tree.unmount());
  await saveConnection({ mode: 'api', url: 'http://192.168.0.3:18099' });
  const oldFetch = globalThis.fetch;
  globalThis.fetch = jest
    .fn()
    .mockRejectedValue(new TypeError('Failed to fetch'));
  try {
    await act(async () => {
      tree = renderer.create(<App />);
    });
    expect(text()).not.toContain('Données simulées');
    expect(text()).toContain('Serveur inaccessible');
    await press('Connexion et paramètres');
    expect(tree.root.findByType(TextInput).props.value).toBe(
      'http://192.168.0.3:18099',
    );
  } finally {
    globalThis.fetch = oldFetch;
  }
});
test('dashboard et navigation compacte sans sac opérationnel', async () => {
  expect(text()).toContain('Données simulées');
  expect(text()).not.toContain('Afficher le sac');
  await press('Stock');
  expect(text()).toContain('LOT-C');
  expect(text()).not.toContain('LOT-S');
  await press('Détail LOT-C');
  expect(text()).toContain('DataMatrix');
  expect(text()).toContain('Stock');
  await press('Retour au stock');
  await press('Mouvements');
  expect(text()).toContain('Stock après opération');
  await press('Alertes');
  expect(text()).not.toContain('compresses:bag');
});
test('recherche et filtre de stock', async () => {
  await press('Stock');
  const input = tree.root.findByType(TextInput);
  await act(async () => input.props.onChangeText('introuvable'));
  expect(text()).toContain('Aucun produit');
  await act(async () => input.props.onChangeText('compresses'));
  expect(text()).toContain('LOT-C');
  await press('Stock faible uniquement');
  expect(text()).toContain('Aucun produit');
});
test('simulation uniquement dans son espace et états vide/chargement réversibles', async () => {
  expect(text()).not.toContain('Simuler un stock vide');
  await press('Connexion et paramètres');
  await press('Ouvrir la démonstration');
  await press('Simuler un stock vide');
  expect(text()).toContain('Aucun produit');
  await press('Connexion et paramètres');
  await press('Ouvrir la démonstration');
  await press('Simuler un chargement');
  expect(text()).toContain('Chargement');
  await press('Terminer le chargement simulé');
  expect(text()).not.toContain('Terminer le chargement simulé');
});
test('erreur API et reprise sans bascule fictive', async () => {
  let fail = true;
  const repository: StockRepository = {
    mode: 'api',
    load: async () => {
      if (fail) throw Error('Réseau coupé');
      return { ...(await new MockStockRepository().load()), simulated: false };
    },
  };
  await act(async () => tree.update(<App repository={repository} />));
  expect(text()).toContain('Réseau coupé');
  expect(text()).not.toContain('Données simulées');
  fail = false;
  await press('Réessayer');
  expect(text()).toContain('API connectée');
  expect(text()).not.toContain('Réseau coupé');
});
test('formulaire de connexion refuse une adresse invalide', async () => {
  await press('Connexion et paramètres');
  const input = tree.root.findByType(TextInput);
  await act(async () => input.props.onChangeText('invalid'));
  await press('Connecter l’API');
  expect(
    tree.root
      .findAllByType(Text)
      .some(n => n.props.accessibilityRole === 'alert'),
  ).toBe(true);
});
test('le polling actualise aussi le détail sélectionné', async () => {
  jest.useFakeTimers();
  let quantity = 40;
  const repository: StockRepository = {
    mode: 'api',
    load: async () => {
      const s = await new MockStockRepository().load();
      return {
        ...s,
        simulated: false,
        batches: s.batches.map(b => ({ ...b, quantity })),
      };
    },
  };
  try {
    await act(async () => tree.update(<App repository={repository} />));
    await press('Stock');
    await press('Détail LOT-C');
    quantity = 39;
    await act(async () => {
      jest.advanceTimersByTime(5000);
    });
    expect(text()).toContain('39');
  } finally {
    await act(async () => tree.update(<App />));
    jest.useRealTimers();
  }
});
test('chargement API sans fausse connexion', async () => {
  const repository: StockRepository = {
    mode: 'api',
    load: () => new Promise(() => {}),
  };
  await act(async () => tree.update(<App repository={repository} />));
  expect(text()).toContain('Connexion en cours');
  expect(text()).not.toContain('API connectée');
  expect(text()).not.toContain('Données simulées');
});
test('breakpoints téléphone, tablette, PC', () => {
  expect(layoutForWidth(320)).toMatchObject({
    navigation: 'bottom',
    columns: 1,
  });
  expect(layoutForWidth(820)).toMatchObject({
    navigation: 'sidebar',
    columns: 2,
  });
  expect(layoutForWidth(1440)).toMatchObject({
    navigation: 'sidebar',
    columns: 4,
  });
});

test('filtrer le sac ne purge pas les données historiques', async () => {
  const original = await new MockStockRepository().load();
  const snapshot = {
    ...original,
    batches: [
      ...original.batches,
      {
        ...original.batches[0],
        id: 'LOT-S',
        location: 'bag' as const,
        quantity: 4,
      },
    ],
  };
  const before = JSON.stringify(snapshot);
  const data = cabinetData(snapshot);
  expect(data.batches.every(b => b.location === 'cabinet')).toBe(true);
  expect(data.alerts.some(a => a.subjectId === 'LOT-S')).toBe(false);
  expect(JSON.stringify(snapshot)).toBe(before);
  expect(snapshot.batches.some(b => b.location === 'bag')).toBe(true);
});

test('le filtre de stock faible additionne les lots comme le moteur Scala', async () => {
  const base = await new MockStockRepository().load();
  const batch = base.batches.find(b => b.location === 'cabinet')!;
  const repository: StockRepository = {
    mode: 'simulated',
    load: async () => ({
      ...base,
      batches: [
        { ...batch, quantity: 3 },
        { ...batch, id: 'LOT-D', quantity: 3 },
      ],
    }),
  };
  await act(async () => tree.update(<App repository={repository} />));
  await press('Stock');
  await press('Stock faible uniquement');
  expect(text()).toContain('Aucun produit');
  expect(text()).not.toContain('Détail LOT-D');
});
