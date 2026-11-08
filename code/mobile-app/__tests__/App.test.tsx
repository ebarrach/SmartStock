import React from 'react';
import renderer, { act, ReactTestRenderer } from 'react-test-renderer';
import { Button, Text } from 'react-native';
import App from '../App';
import { MockStockRepository, StockRepository } from '../src/data/repository';

jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
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
async function press(title: string) {
  const button = tree.root
    .findAllByType(Button)
    .find(node => node.props.title === title);
  if (!button) {
    throw new Error(`Bouton absent : ${title}`);
  }
  await act(async () => button.props.onPress());
}
function text() {
  return tree.root
    .findAllByType(Text)
    .map(node => node.props.children)
    .flat()
    .join(' ');
}
test('stock armoire et sac puis detail et retour', async () => {
  expect(text()).toContain('données simulées');
  expect(text()).toContain('40');
  await press('Afficher le sac');
  expect(text()).toContain('Sac');
  await press('Détail Compresses');
  expect(text()).toContain('LOT-S');
  expect(text()).toContain('DataMatrix');
  await press('Retour au stock');
  expect(text()).toContain('Emplacement');
});
test('etats vide et chargement accessibles', async () => {
  await press('Simuler un stock vide');
  expect(text()).toContain('Aucun produit');
  await press('Simuler un chargement');
  expect(text()).toContain('Chargement simulé');
  await press('Terminer le chargement simulé');
  expect(text()).toContain('Aucun produit');
});
test('deconnexion visible et reconnexion explicite', async () => {
  await press('Connexion');
  await press('Simuler une déconnexion');
  expect(text()).toContain('Hors connexion');
  await press('Stock');
  const refresh = tree.root
    .findAllByType(Button)
    .find(node => node.props.title === 'Simuler un chargement');
  expect(refresh?.props.disabled).toBe(true);
  await press('Connexion');
  await press('Simuler une reconnexion');
  expect(text()).toContain('Connexion simulée');
});
test('mouvements et alertes restent explicitement simules', async () => {
  await press('Mouvements');
  expect(text()).toContain('Historique fictif');
  await press('Alertes');
  expect(text()).toContain('Aucune notification réelle');
});

test('erreur reelle puis nouvelle tentative sans repli silencieux', async () => {
  let fail = true;
  let pending:
    | Promise<Awaited<ReturnType<MockStockRepository['load']>>>
    | undefined;
  let resolvePending:
    | ((value: Awaited<ReturnType<MockStockRepository['load']>>) => void)
    | undefined;
  const repository: StockRepository = {
    mode: 'api',
    load: async () => {
      if (fail) {
        throw new Error('Reseau coupe');
      }
      if (pending) {
        return pending;
      }
      return { ...(await new MockStockRepository().load()), simulated: false };
    },
  };
  await act(async () => {
    tree.update(<App repository={repository} />);
  });
  expect(text()).toContain('Reseau coupe');
  fail = false;
  pending = new Promise(resolve => {
    resolvePending = resolve;
  });
  await press('Réessayer');
  expect(text()).toContain('Reseau coupe');
  const recovered = {
    ...(await new MockStockRepository().load()),
    simulated: false,
  };
  await act(async () => {
    resolvePending?.(recovered);
  });
  expect(text()).toContain('API connectée');
  expect(text()).toContain('40');
});

test('le polling API actualise aussi le detail du lot selectionne', async () => {
  jest.useFakeTimers();
  let quantity = 40;
  const repository: StockRepository = {
    mode: 'api',
    load: async () => {
      const snapshot = await new MockStockRepository().load();
      return {
        ...snapshot,
        simulated: false,
        batches: snapshot.batches.map(b =>
          b.id === 'LOT-C' ? { ...b, quantity } : b,
        ),
      };
    },
  };
  try {
    await act(async () => {
      tree.update(<App repository={repository} />);
    });
    await press('Détail Compresses');
    expect(text()).toContain('40');
    quantity = 39;
    await act(async () => {
      jest.advanceTimersByTime(5000);
    });
    expect(text()).toContain('39');
  } finally {
    await act(async () => {
      tree.update(<App />);
    });
    jest.useRealTimers();
  }
});

test('le mode API attend une reponse et ne propose aucun controle de simulation', async () => {
  let resolveLoad!: (
    value: Awaited<ReturnType<MockStockRepository['load']>>,
  ) => void;
  const repository: StockRepository = {
    mode: 'api',
    load: () =>
      new Promise(resolve => {
        resolveLoad = resolve;
      }),
  };
  // Unmount the previous mock so no snapshot can be mistaken for an API response.
  await act(async () => tree.unmount());
  await act(async () => {
    tree = renderer.create(<App repository={repository} />);
  });
  expect(text()).toContain('Connexion à l’API');
  expect(text()).not.toContain('API connectée');
  expect(text()).not.toContain('Chargement simulé');
  expect(
    tree.root
      .findAllByType(Button)
      .some(b => String(b.props.title).includes('Simuler')),
  ).toBe(false);
  await act(async () =>
    resolveLoad({
      ...(await new MockStockRepository().load()),
      simulated: false,
    }),
  );
  expect(text()).toContain('API connectée');
  await press('Connexion');
  expect(
    tree.root
      .findAllByType(Button)
      .some(b => String(b.props.title).includes('Simuler')),
  ).toBe(false);
});
