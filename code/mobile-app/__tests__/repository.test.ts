import {
  ApiStockRepository,
  MockStockRepository,
  parseSnapshot,
} from '../src/data/repository';

test('le mock respecte le contrat de snapshot et conserve les lots separes', async () => {
  const snapshot = await new MockStockRepository().load();
  expect(snapshot.simulated).toBe(true);
  expect(snapshot.batches.map(b => b.location)).toEqual(['cabinet', 'bag']);
  expect(snapshot.batches.map(b => b.quantity)).toEqual([40, 4]);
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
