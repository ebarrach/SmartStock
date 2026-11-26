import {
  ApiStockRepository,
  MockStockRepository,
  parseSnapshot,
} from '../src/data/repository';

test('delai reseau explicite puis reprise API sans donnees fictives', async () => {
  const snapshot = {
    ...(await new MockStockRepository().load()),
    simulated: false,
  };
  let available = false;
  const transport = jest.fn((_url: unknown, init?: RequestInit) => {
    if (available)
      return Promise.resolve({
        ok: true,
        json: async () => snapshot,
      } as Response);
    return new Promise<Response>((_resolve, reject) =>
      init?.signal?.addEventListener('abort', () => {
        const error = new Error('aborted');
        error.name = 'AbortError';
        reject(error);
      }),
    );
  });
  jest.useFakeTimers();
  try {
    const repository = new ApiStockRepository(
      'http://192.168.0.143:18099',
      transport,
    );
    const pending = repository.load().then(
      () => '',
      (error: Error) => error.message,
    );
    await jest.advanceTimersByTimeAsync(5000);
    expect(await pending).toContain('Délai dépassé');
    available = true;
    await expect(repository.load()).resolves.toEqual(snapshot);
  } finally {
    jest.useRealTimers();
  }
});

test('le fetch navigateur conserve son contexte global', async () => {
  const original = globalThis.fetch;
  const snapshot = await new MockStockRepository().load();
  globalThis.fetch = jest.fn(function (this: unknown) {
    expect(this).toBe(globalThis);
    return Promise.resolve({
      ok: true,
      json: async () => snapshot,
    } as Response);
  });
  try {
    await expect(
      new ApiStockRepository('http://localhost:8081').load(),
    ).resolves.toEqual(snapshot);
  } finally {
    globalThis.fetch = original;
  }
});

test('le mock respecte le contrat de snapshot et conserve les lots separes', async () => {
  const snapshot = await new MockStockRepository().load();
  expect(snapshot.simulated).toBe(true);
  expect(snapshot.products).toHaveLength(7);
  expect(snapshot.batches.every(b => b.location === 'cabinet')).toBe(true);
  expect(snapshot.movements.some(m => m.kind === 'consumption')).toBe(true);
});
test('le repository HTTP utilise le meme contrat et signale le mode recu', async () => {
  const demo = await new MockStockRepository().load();
  const transport = jest.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ ...demo, simulated: false }),
  });
  const repository = new ApiStockRepository(
    'http://127.0.0.1:8081/',
    transport,
  );
  expect((await repository.load()).simulated).toBe(false);
  expect(transport.mock.calls[0][0]).toBe(
    'http://127.0.0.1:8081/api/v1/snapshot',
  );
});
test('les erreurs HTTP et JSON ne deviennent pas de fausses donnees simulees', async () => {
  const unavailable = new ApiStockRepository(
    'http://test',
    jest.fn().mockResolvedValue({ ok: false, status: 503 }),
  );
  await expect(unavailable.load()).rejects.toThrow('503');
  const invalid = new ApiStockRepository(
    'http://test',
    jest.fn().mockResolvedValue({ ok: true, json: async () => ({}) }),
  );
  await expect(invalid.load()).rejects.toThrow('API');
});
test('les quantites negatives et references inconnues sont rejetees', async () => {
  const demo = await new MockStockRepository().load();
  expect(() =>
    parseSnapshot({ ...demo, batches: [{ ...demo.batches[0], quantity: -1 }] }),
  ).toThrow();
  expect(() =>
    parseSnapshot({
      ...demo,
      batches: [{ ...demo.batches[0], productId: 'unknown' }],
    }),
  ).toThrow();
});
