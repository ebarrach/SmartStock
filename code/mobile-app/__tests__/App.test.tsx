import React from 'react';
import renderer, { act, ReactTestRenderer } from 'react-test-renderer';
import { Button, Text } from 'react-native';
import App from '../App';

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
