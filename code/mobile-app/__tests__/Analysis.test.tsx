import React from 'react';
import renderer, { act, ReactTestRenderer } from 'react-test-renderer';
import { AnalysisScreen } from '../src/ui/AnalysisScreen';
import { MockStockRepository, StockRepository } from '../src/data/repository';
import { parseAnalysis } from '../src/data/analysis';
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
let tree: ReactTestRenderer;
afterEach(async () => {
  if (tree) await act(async () => tree.unmount());
});
async function press(label: string) {
  const n = tree.root.findAll(
    v =>
      v.props.accessibilityLabel === label &&
      typeof v.props.onPress === 'function',
  )[0];
  if (!n) throw Error(label);
  await act(async () => n.props.onPress());
}
const text = () => JSON.stringify(tree.toJSON());
test('graphiques, choix de periode et de capteur sans remplacer les absences par zero', async () => {
  const repository = new MockStockRepository();
  await act(async () => {
    tree = renderer.create(<AnalysisScreen repository={repository} />);
  });
  expect(text()).toContain('Compresses');
  await press('12 mois');
  expect(text()).toContain('Sans données');
  await press('Température');
  expect(text()).toContain('Température quotidienne');
  await press('Suivant');
  expect(text()).toContain('mesures');
  await press('Humidité');
  expect(text()).toContain('Humidité quotidienne');
});
test('suggestions viennent du repository, affichent calcul et limites sans commander', async () => {
  const repository = new MockStockRepository();
  const report = await repository.analyse(6);
  await act(async () => {
    tree = renderer.create(
      <AnalysisScreen repository={repository} suggestions />,
    );
  });
  expect(text()).toContain('Aide à la décision');
  expect(text()).toContain(
    `Réapprovisionnement suggéré : ${report.products[0].suggestion.quantity}`,
  );
  expect(text()).toContain('Consommation observée');
  expect(text()).not.toContain('Commander maintenant');
});
test('une analyse API indisponible reste une erreur, jamais une suggestion de demonstration', async () => {
  const fixture = await new MockStockRepository().analyse(6);
  let fail = true;
  const repository: StockRepository = {
    mode: 'api',
    load: () => new MockStockRepository().load(),
    analyse: async () => {
      if (fail) throw Error('Analyse API indisponible');
      return { ...fixture, exampleData: false };
    },
  };
  await act(async () => {
    tree = renderer.create(
      <AnalysisScreen repository={repository} suggestions />,
    );
  });
  expect(text()).toContain('Analyse API indisponible');
  expect(text()).not.toContain('Réapprovisionnement suggéré');
  fail = false;
  await press('Actualiser les analyses');
  expect(text()).toContain('Réapprovisionnement suggéré');
});
test('le client refuse une suggestion negative ou une serie invalide', async () => {
  const fixture = await new MockStockRepository().analyse(6);
  expect(() =>
    parseAnalysis({
      ...fixture,
      products: [
        {
          ...fixture.products[0],
          suggestion: { ...fixture.products[0].suggestion, quantity: -10 },
        },
      ],
    }),
  ).toThrow();
  expect(() =>
    parseAnalysis({
      ...fixture,
      sensors: [{ day: 'bad', count: 0, mean: null, min: null, max: null }],
    }),
  ).toThrow();
});
