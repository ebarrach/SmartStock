import React from 'react';
import renderer, { act } from 'react-test-renderer';
import { ConnectionProfiles } from '../src/ui/ConnectionProfiles';
import storage from '../src/data/connectionStorage';
import { loadConnection } from '../src/data/connection';

test('teste puis sélectionne une adresse privée inédite et conserve son choix', async () => {
  const address = 'http://10.42.8.91:18119';
  await storage.setItem(
    'connection-profiles-v1',
    JSON.stringify([
      { id: 'home', name: 'Maison', url: 'http://192.168.0.143:18119' },
      { id: 'new', name: 'Nouvelle IP', url: address },
    ]),
  );
  await storage.setItem('native-scanner-pending-v1', 'null');
  const original = globalThis.fetch;
  const transport = jest.fn(
    async () =>
      ({ ok: true, json: async () => ({ status: 'ok' }) } as Response),
  );
  globalThis.fetch = transport;
  const selected = jest.fn();
  let tree: renderer.ReactTestRenderer;
  try {
    await act(async () => {
      tree = renderer.create(
        <ConnectionProfiles
          activeUrl="http://192.168.0.143:18119"
          select={selected}
        />,
      );
    });
    const press = async (label: string) =>
      act(async () => {
        await tree!.root
          .findAll(
            n =>
              n.props.accessibilityLabel === label &&
              typeof n.props.onPress === 'function',
          )[0]
          .props.onPress();
      });
    await press('Tester Nouvelle IP');
    expect(transport).toHaveBeenCalledWith(
      address + '/api/v1/health',
      expect.anything(),
    );
    await press('Sélectionner Nouvelle IP');
    expect(selected).toHaveBeenCalledWith(address);
    expect((await loadConnection(false))?.url).toBe(address);
    expect(await storage.getItem('connection-profiles-v1')).toContain('Maison');
  } finally {
    globalThis.fetch = original;
    await act(async () => tree!.unmount());
  }
});

test('un test réseau échoué conserve les profils sans sélection automatique', async () => {
  const saved = [
    { id: 'home', name: 'Maison', url: 'http://192.168.0.143:18119' },
  ];
  await storage.setItem('connection-profiles-v1', JSON.stringify(saved));
  const original = globalThis.fetch;
  globalThis.fetch = jest.fn(async () => {
    throw Error('Réseau indisponible');
  });
  const select = jest.fn();
  let tree: renderer.ReactTestRenderer;
  try {
    await act(async () => {
      tree = renderer.create(
        <ConnectionProfiles activeUrl={saved[0].url} select={select} />,
      );
    });
    await act(async () => {
      await tree!.root
        .findAll(
          n =>
            n.props.accessibilityLabel === 'Tester Maison' &&
            typeof n.props.onPress === 'function',
        )[0]
        .props.onPress();
    });
    expect(
      JSON.parse((await storage.getItem('connection-profiles-v1'))!),
    ).toEqual(saved);
    expect(select).not.toHaveBeenCalled();
    expect(JSON.stringify(tree!.toJSON())).toContain('Réseau indisponible');
  } finally {
    globalThis.fetch = original;
    await act(async () => tree!.unmount());
  }
});

test('modifie et supprime un profil secondaire sans perdre Maison', async () => {
  await storage.setItem(
    'connection-profiles-v1',
    JSON.stringify([
      { id: 'home', name: 'Maison', url: 'http://192.168.0.143:18119' },
      { id: 'hotspot', name: 'Hotspot', url: 'http://10.100.76.76:18119' },
    ]),
  );
  let tree: renderer.ReactTestRenderer;
  await act(async () => {
    tree = renderer.create(
      <ConnectionProfiles
        activeUrl="http://192.168.0.143:18119"
        select={jest.fn()}
      />,
    );
  });
  const press = async (label: string) =>
    act(async () => {
      await tree!.root
        .findAll(
          n =>
            n.props.accessibilityLabel === label &&
            typeof n.props.onPress === 'function',
        )[0]
        .props.onPress();
    });
  await press('Modifier Hotspot');
  await act(async () =>
    tree!.root
      .findAll(
        n =>
          n.props.accessibilityLabel === 'URL du profil' &&
          typeof n.props.onChangeText === 'function',
      )[0]
      .props.onChangeText('http://10.100.76.77:18119'),
  );
  await press('Enregistrer le profil modifié');
  expect(await storage.getItem('connection-profiles-v1')).toContain(
    '10.100.76.77',
  );
  await press('Supprimer Hotspot');
  expect(
    JSON.parse((await storage.getItem('connection-profiles-v1'))!),
  ).toHaveLength(1);
  expect(await storage.getItem('connection-profiles-v1')).toContain('Maison');
  await act(async () => tree!.unmount());
});

test('importe Maison et conserve plusieurs profils après remontage', async () => {
  await storage.setItem(
    'connection-profiles-v1',
    JSON.stringify([
      { id: 'home', name: 'Maison', url: 'http://192.168.0.143:18119' },
    ]),
  );
  let tree: renderer.ReactTestRenderer;
  await act(async () => {
    tree = renderer.create(
      <ConnectionProfiles
        activeUrl="http://192.168.0.143:18119"
        select={jest.fn()}
      />,
    );
  });
  const field = (label: string) =>
    tree!.root.findAll(
      n =>
        n.props.accessibilityLabel === label &&
        typeof n.props.onChangeText === 'function',
    )[0];
  await act(async () => {
    field('Nom du profil').props.onChangeText('Point d’accès téléphone');
    field('URL du profil').props.onChangeText('http://10.100.76.76:18119');
  });
  await act(async () => {
    await tree!.root
      .findAll(
        n =>
          n.props.accessibilityLabel === 'Ajouter le profil' &&
          typeof n.props.onPress === 'function',
      )[0]
      .props.onPress();
  });
  await act(async () => tree!.unmount());
  await act(async () => {
    tree = renderer.create(
      <ConnectionProfiles
        activeUrl="http://192.168.0.143:18119"
        select={jest.fn()}
      />,
    );
  });
  expect(JSON.stringify(tree!.toJSON())).toContain('Point d’accès téléphone');
  expect(JSON.stringify(tree!.toJSON())).toContain('Maison');
  await act(async () => tree!.unmount());
});

test('refuse le changement de serveur lorsqu’une opération est en attente', async () => {
  await storage.setItem(
    'connection-profiles-v1',
    JSON.stringify([
      { id: 'other', name: 'Hotspot', url: 'http://10.100.76.76:18119' },
    ]),
  );
  await storage.setItem(
    'native-scanner-pending-v1',
    JSON.stringify({
      baseUrl: 'http://192.168.0.143:18119',
      operationId: 'unchanged',
    }),
  );
  const select = jest.fn();
  let tree: renderer.ReactTestRenderer;
  await act(async () => {
    tree = renderer.create(
      <ConnectionProfiles
        activeUrl="http://192.168.0.143:18119"
        select={select}
      />,
    );
  });
  await act(async () => {
    await tree!.root
      .findAll(
        n =>
          n.props.accessibilityLabel === 'Sélectionner Hotspot' &&
          typeof n.props.onPress === 'function',
      )[0]
      .props.onPress();
  });
  expect(select).not.toHaveBeenCalled();
  expect(JSON.stringify(tree!.toJSON())).toContain('Opération en attente');
  expect(await storage.getItem('native-scanner-pending-v1')).toContain(
    'unchanged',
  );
  await storage.setItem('native-scanner-pending-v1', 'null');
  await act(async () => tree!.unmount());
});
