import React from 'react';
import renderer, { act } from 'react-test-renderer';
import { ServerSettingsPanel } from '../src/ui/ServerSettingsPanel';
import { ActivityScreen, cabinetData } from '../src/ui/InventoryScreens';
import { MockStockRepository } from '../src/data/repository';
jest.mock('../src/scanner/identifiers', () => ({
  operationUUID: () => '12345678-1234-4234-8234-123456789012',
}));
jest.mock('../src/ui/icons', () => ({}));

test('affiche le mode du serveur et envoie un choix explicite avec identifiant de commande', async () => {
  const oldFetch = globalThis.fetch;
  let mode = false;
  const fetcher = jest.fn(async (_url: unknown, init?: RequestInit) => {
    if (init?.method === 'PUT')
      mode = JSON.parse(String(init.body)).withoutSensors;
    return {
      ok: true,
      json: async () => ({
        withoutSensors: mode,
        persistent: true,
        scales: [],
        tare: null,
        calibration: null,
      }),
    } as Response;
  });
  globalThis.fetch = fetcher;
  let tree: renderer.ReactTestRenderer;
  const changed = jest.fn();
  try {
    await act(async () => {
      tree = renderer.create(
        <ServerSettingsPanel
          baseUrl="http://192.168.0.143:18119"
          onChanged={changed}
        />,
      );
    });
    expect(JSON.stringify(tree!.toJSON())).toContain('Aucune balance détectée');
    const toggle = tree!.root.findAll(
      n =>
        n.props.accessibilityLabel === 'Mode sans capteurs' &&
        typeof n.props.onValueChange === 'function',
    )[0];
    await act(async () => {
      await toggle.props.onValueChange(true);
    });
    expect(mode).toBe(true);
    expect(changed).toHaveBeenCalledTimes(1);
    const write = fetcher.mock.calls.find(c => c[1]?.method === 'PUT');
    expect(write?.[0]).toBe(
      'http://192.168.0.143:18119/api/v1/settings/sensors',
    );
    expect(write?.[1]?.headers).toHaveProperty('X-Command-Id');
    expect(JSON.stringify(tree!.toJSON())).toContain('Sans capteurs');
  } finally {
    await act(async () => tree!.unmount());
    globalThis.fetch = oldFetch;
  }
});

test('masque uniquement le produit archive du stock et conserve ses mouvements et les noms des alertes', async () => {
  const snapshot = await new MockStockRepository().load();
  const product = snapshot.products[0];
  const original = cabinetData(snapshot);
  const archived = cabinetData({
    ...snapshot,
    archivedProductIds: [product.id],
  });
  expect(archived.batches.some(b => b.productId === product.id)).toBe(false);
  expect(archived.movements).toEqual(original.movements);
  let tree: renderer.ReactTestRenderer;
  await act(async () => {
    tree = renderer.create(
      <ActivityScreen
        alerts
        snapshot={{
          ...snapshot,
          alerts: [
            {
              code: 'low_stock',
              subjectId: `${product.id}:cabinet`,
              message: 'Stock bas',
            },
          ],
        }}
      />,
    );
  });
  expect(JSON.stringify(tree!.toJSON())).toContain(product.name);
  await act(async () => tree!.unmount());
});
