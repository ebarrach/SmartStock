import { decodeGS1 } from '../src/scanner/gs1';
import { ScanSession, ScanStorage } from '../src/scanner/ScanSession';

const CODE = '(01)05400711223344(17)291231(10)USB(21)USB0001';
const snapshot = {
  products: [
    {
      id: 'p',
      name: 'Produit reconnu',
      dataMatrix: '05400711223344',
      unitWeightGrams: 10,
      minStock: 0,
    },
  ],
  batches: [
    {
      id: 'b',
      productId: 'p',
      location: 'cabinet',
      quantity: 0,
      expiresOn: '2029-12-31',
      lotCode: 'USB',
    },
  ],
  movements: [],
  readings: [],
  alerts: [],
  simulated: true,
};
function setup(normal = false) {
  let saved: string | null = null,
    counter = 0;
  const storage: ScanStorage = {
    getItem: async () => saved,
    setItem: async (_key, value) => {
      saved = value;
    },
  };
  const uuid = () =>
    `00000000-0000-4000-8000-${String(++counter).padStart(12, '0')}`;
  const confirmed = jest.fn(async () => {});
  const read = jest.fn();
  const transport = jest.fn(async (url: unknown, init?: RequestInit) => {
    const path = String(url);
    if (path.endsWith('/snapshot')) return reply(snapshot);
    if (path.endsWith('/scan-only-test') && !init?.body)
      return reply({ enabled: !normal, allowed: !normal });
    if (path.includes('/operations/'))
      return reply({ code: 'operation_unknown', message: 'Non reçu' }, 404);
    if (path.endsWith('/events')) return reply({ status: 'ok' });
    const id = (init?.headers as Record<string, string>)['X-Command-Id'];
    return reply({
      movement: {
        id: 'm',
        eventId: id,
        batchId: 'b',
        delta: 1,
        quantityAfter: 1,
      },
    });
  });
  const session = new ScanSession(
    storage,
    uuid,
    () => {},
    confirmed,
    transport as typeof fetch,
    read,
  );
  return { session, storage, uuid, confirmed, transport, read };
}
function reply(value: unknown, status = 200): Response {
  return { ok: status < 400, status, json: async () => value } as Response;
}

test('GS1 conserve ASCII29, lot/serie, symbologie et date de fin de mois', () => {
  const raw = ']d201054007112233441729123110USB\u001d21USB0001';
  expect(decodeGS1(raw)).toEqual({
    raw,
    gtin: '05400711223344',
    lot: 'USB',
    serial: 'USB0001',
    expiration: '2029-12-31',
  });
  expect(decodeGS1(CODE).serial).toBe('USB0001');
  expect(decodeGS1('(01)05400711223344(17)280200(10)A').expiration).toBe(
    '2028-02-29',
  );
  expect(decodeGS1('(01)05400711223344').lot).toBeUndefined();
  for (const rawCode of [
    'QR quelconque',
    '(01)12',
    '(01)05400711223344(17)290231',
    CODE + '(21)OTHER',
  ])
    expect(() => decodeGS1(rawCode)).toThrow();
});

test('une detection verrouille les frames puis impose un rearmement explicite', async () => {
  const { session, transport, confirmed } = setup();
  await Promise.all([
    session.detect([CODE], 'http://api'),
    session.detect([CODE], 'http://api'),
  ]);
  const first = session.state.pending!.operationId;
  expect(transport.mock.calls.filter(([, init]) => init?.body)).toHaveLength(1);
  expect(session.state.phase).toBe('confirmed');
  expect(confirmed).toHaveBeenCalledTimes(1);
  await session.detect([CODE], 'http://api');
  expect(session.state.pending!.operationId).toBe(first);
  await session.reset();
  await session.detect([CODE], 'http://api');
  expect(session.state.pending!.operationId).not.toBe(first);
  expect(transport.mock.calls.filter(([, init]) => init?.body)).toHaveLength(2);
});

test('timeout apres ecriture puis reprise locale reconcilie le meme ID sans seconde bascule', async () => {
  const context = setup();
  let committed: string | undefined;
  const original = context.transport.getMockImplementation()!;
  context.transport.mockImplementation(async (url, init) => {
    if (String(url).includes('/operations/') && committed)
      return reply({
        status: 'confirmed',
        movement: { eventId: committed, delta: 1, quantityAfter: 1 },
      });
    if (init?.body) {
      committed = (init.headers as Record<string, string>)['X-Command-Id'];
      throw new TypeError('response lost');
    }
    return original(url, init);
  });
  await context.session.detect([CODE], 'http://api');
  expect(context.session.state.uncertain).toBe(true);
  await context.session.reset();
  expect(context.session.locked).toBe(true);
  const resumed = new ScanSession(
    context.storage,
    context.uuid,
    () => {},
    context.confirmed,
    context.transport as typeof fetch,
  );
  await resumed.restore();
  expect(resumed.state.pending!.operationId).toBe(committed);
  await resumed.submit();
  expect(resumed.state.phase).toBe('confirmed');
  expect(
    context.transport.mock.calls.filter(([, init]) => init?.body),
  ).toHaveLength(1);
});

test('le mode normal envoie seulement le scan, attend une vraie confirmation et ne fabrique pas de poids', async () => {
  const { session, transport, confirmed } = setup(true);
  const original = transport.getMockImplementation()!;
  transport.mockImplementation(async (url, init) =>
    String(url).includes('/operations/')
      ? reply({ status: 'pending', movement: null })
      : original(url, init),
  );
  await session.detect([CODE], 'http://api');
  expect(session.state.phase).toBe('waiting');
  expect(confirmed).not.toHaveBeenCalled();
  const frames = transport.mock.calls
    .filter(([, init]) => init?.body)
    .map(([, init]) => JSON.parse(String(init!.body)));
  expect(frames).toHaveLength(1);
  expect(frames[0].type).toBe('datamatrix.detected');
  expect(frames[0].data.serial).toBe('USB0001');
  expect(frames[0].data.operationId).toBe(session.state.pending!.operationId);
});

test('codes invalides, plusieurs boites et stockage local en echec ne lancent aucune commande', async () => {
  for (const codes of [
    ['invalide'],
    [CODE, CODE.replace('USB0001', 'OTHER')],
  ]) {
    const { session, transport } = setup();
    await session.detect(codes, 'http://api');
    expect(session.state.phase).toBe('error');
    expect(transport).not.toHaveBeenCalled();
  }
  const { session, storage, transport } = setup();
  storage.setItem = async () => {
    throw Error('disk unavailable');
  };
  await session.detect([CODE], 'http://api');
  expect(transport).not.toHaveBeenCalled();
});

test('presence inconnue exige un choix explicite avec un nouvel ID', async () => {
  const { session, transport } = setup();
  const original = transport.getMockImplementation()!;
  transport.mockImplementation(async (url, init) => {
    if (init?.body && JSON.parse(String(init.body)).direction === 'auto')
      return reply(
        { code: 'presence_unknown', message: 'Choisir une action' },
        409,
      );
    return original(url, init);
  });
  await session.detect([CODE], 'http://api');
  const id = session.state.pending!.operationId;
  expect(session.state.choice).toBe(true);
  await session.submit('entry');
  expect(session.state.pending!.operationId).not.toBe(id);
  expect(session.state.phase).toBe('confirmed');
});

test('erreur serveur apres envoi garde le verrou et les identifiants', async () => {
  const { session, transport } = setup();
  const original = transport.getMockImplementation()!;
  transport.mockImplementation(async (url, init) =>
    init?.body
      ? reply(
          { code: 'storage_unavailable', message: 'Ecriture non confirmee' },
          503,
        )
      : original(url, init),
  );
  await session.detect([CODE], 'http://api');
  const id = session.state.pending!.operationId;
  expect(session.state.uncertain).toBe(true);
  await session.reset();
  expect(session.state.pending!.operationId).toBe(id);
});

test('un bip de lecture par session, jamais sur retry ou chaque image', async () => {
  const { session, read } = setup();
  await session.detect([CODE], 'http://api');
  await session.detect([CODE], 'http://api');
  expect(read).toHaveBeenCalledTimes(1);
  await session.reset();
  await session.detect([CODE], 'http://api');
  expect(read).toHaveBeenCalledTimes(2);
});

test('normalisation GTIN conserve les zeros et permet de retrouver une fiche GTIN13', async () => {
  const { normalizeGTIN } = require('../src/scanner/gs1');
  expect(normalizeGTIN('07613421008642')).toBe('07613421008642');
  expect(normalizeGTIN('7613421008642')).toBe('07613421008642');
});

test('produit inconnu puis enregistrement sans masse, retry et ajout separe', async () => {
  const context = setup();
  const data = {
    ...snapshot,
    products: [] as typeof snapshot.products,
    batches: [] as typeof snapshot.batches,
  };
  const writes: { path: string; body: any; id: string }[] = [];
  let loseFirstProductReply = true;
  context.transport.mockImplementation(async (url, init) => {
    const path = String(url);
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    if (path.endsWith('/snapshot')) return reply(data);
    if (path.endsWith('/scan-only-test') && !body)
      return reply({ enabled: true, allowed: true });
    if (path.includes('/operations/'))
      return reply({ code: 'operation_unknown', message: 'Non reçu' }, 404);
    const id = (init!.headers as Record<string, string>)['X-Command-Id'];
    writes.push({ path, body, id });
    if (path.endsWith('/products')) {
      data.products.push(body);
      if (loseFirstProductReply) {
        loseFirstProductReply = false;
        throw new TypeError('reply lost');
      }
      return reply(data);
    }
    if (path.endsWith('/batches/empty')) {
      data.batches.push(body);
      return reply(data);
    }
    return reply({ movement: { eventId: id, delta: 1, quantityAfter: 1 } });
  });
  await context.session.detect([CODE], 'http://api');
  expect(context.session.state.issue).toBe('unknown_product');
  await context.session.register('Nom réellement saisi');
  expect(context.session.state.uncertain).toBe(true);
  await context.session.submit();
  expect(context.session.state.phase).toBe('review');
  expect(data.products[0].name).toBe('Nom réellement saisi');
  expect(data.products[0].unitWeightGrams).toBeNull();
  expect(data.batches[0].quantity).toBe(0);
  expect(writes.filter(w => w.path.endsWith('/products'))).toHaveLength(1);
  expect(writes.filter(w => w.path.endsWith('/scan-only-test'))).toHaveLength(
    0,
  );
  await context.session.submit('entry');
  expect(context.session.state.phase).toBe('confirmed');
});

test('nouveau lot utilise la fiche existante et ne recree pas le produit', async () => {
  const context = setup();
  const data = { ...snapshot, batches: [] as typeof snapshot.batches };
  const posts: string[] = [];
  context.transport.mockImplementation(async (url, init) => {
    const path = String(url);
    if (path.endsWith('/snapshot')) return reply(data);
    if (!init?.body) return reply({ enabled: true, allowed: true });
    posts.push(path);
    data.batches.push(JSON.parse(String(init.body)));
    return reply(data);
  });
  await context.session.detect([CODE], 'http://api');
  expect(context.session.state.issue).toBe('new_batch');
  expect(context.session.state.product).toBe('Produit reconnu');
  await context.session.register();
  expect(posts).toHaveLength(1);
  expect(posts[0]).toContain('/batches/empty');
  expect(data.batches[0].quantity).toBe(0);
});

test('correspondances produits ambigues ne proposent aucune creation automatique', async () => {
  const { session, transport } = setup();
  const ambiguous = {
    ...snapshot,
    products: [
      ...snapshot.products,
      { ...snapshot.products[0], id: 'duplicate' },
    ],
  };
  const original = transport.getMockImplementation()!;
  transport.mockImplementation(async (url, init) =>
    String(url).endsWith('/snapshot') ? reply(ambiguous) : original(url, init),
  );
  await session.detect([CODE], 'http://api');
  expect(session.state.issue).toBe('ambiguous_product');
  await session.register('Ne doit pas etre cree');
  expect(transport.mock.calls.filter(([, init]) => init?.body)).toHaveLength(0);
});
