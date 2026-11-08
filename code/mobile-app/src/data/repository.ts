import demo from '../../../shared/api/examples/snapshot.json';

export type Product = Readonly<{
  id: string;
  name: string;
  dataMatrix: string;
  unitWeightGrams: number;
  minStock: number;
}>;
export type Batch = Readonly<{
  id: string;
  productId: string;
  location: 'cabinet' | 'bag';
  quantity: number;
  expiresOn: string;
}>;
export type Movement = Readonly<{
  id: string;
  batchId: string;
  delta: number;
  quantityAfter: number;
  occurredAt: string;
  eventId: string;
}>;
export type Reading = Readonly<{
  kind: string;
  value: number;
  source: string;
  observedAt: string;
}>;
export type Alert = Readonly<{
  code: string;
  subjectId: string;
  message: string;
}>;
export type Snapshot = Readonly<{
  products: readonly Product[];
  batches: readonly Batch[];
  movements: readonly Movement[];
  readings: readonly Reading[];
  alerts: readonly Alert[];
  simulated: boolean;
}>;
export interface StockRepository {
  readonly mode: 'simulated' | 'api';
  load(signal?: AbortSignal): Promise<Snapshot>;
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('Réponse API invalide');
  }
  return value as Record<string, unknown>;
}
export function parseSnapshot(value: unknown): Snapshot {
  const data = record(value);
  for (const key of [
    'products',
    'batches',
    'movements',
    'readings',
    'alerts',
  ]) {
    if (!Array.isArray(data[key])) {
      throw new Error(`Champ API invalide : ${key}`);
    }
  }
  if (typeof data.simulated !== 'boolean') {
    throw new Error('Mode API absent');
  }
  const strings = (item: Record<string, unknown>, keys: string[]) =>
    keys.every(key => typeof item[key] === 'string');
  const finite = (n: unknown) => typeof n === 'number' && Number.isFinite(n);
  for (const raw of data.products as unknown[]) {
    const p = record(raw);
    if (
      !strings(p, ['id', 'name', 'dataMatrix']) ||
      !finite(p.unitWeightGrams) ||
      Number(p.unitWeightGrams) <= 0 ||
      !Number.isInteger(p.minStock) ||
      Number(p.minStock) < 0
    ) {
      throw new Error('Produit API invalide');
    }
  }
  const products = new Set((data.products as Product[]).map(p => p.id));
  for (const raw of data.batches as unknown[]) {
    const b = record(raw);
    if (
      !strings(b, ['id', 'productId', 'expiresOn']) ||
      !products.has(String(b.productId)) ||
      !['cabinet', 'bag'].includes(String(b.location)) ||
      !Number.isInteger(b.quantity) ||
      Number(b.quantity) < 0
    ) {
      throw new Error('Lot API invalide');
    }
  }
  for (const raw of data.movements as unknown[]) {
    const m = record(raw);
    if (
      !strings(m, ['id', 'batchId', 'occurredAt', 'eventId']) ||
      !Number.isInteger(m.delta) ||
      !Number.isInteger(m.quantityAfter) ||
      Number(m.quantityAfter) < 0
    ) {
      throw new Error('Mouvement API invalide');
    }
  }
  for (const raw of data.readings as unknown[]) {
    const r = record(raw);
    if (!strings(r, ['kind', 'source', 'observedAt']) || !finite(r.value)) {
      throw new Error('Mesure API invalide');
    }
  }
  for (const raw of data.alerts as unknown[]) {
    if (!strings(record(raw), ['code', 'subjectId', 'message'])) {
      throw new Error('Alerte API invalide');
    }
  }
  return data as unknown as Snapshot;
}

export class MockStockRepository implements StockRepository {
  readonly mode = 'simulated' as const;
  async load(): Promise<Snapshot> {
    return parseSnapshot(JSON.parse(JSON.stringify(demo)));
  }
}
export class ApiStockRepository implements StockRepository {
  readonly mode = 'api' as const;
  constructor(
    private readonly baseUrl: string,
    private readonly transport: typeof fetch = fetch,
  ) {}
  async load(signal?: AbortSignal): Promise<Snapshot> {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) {
      controller.abort();
    }
    signal?.addEventListener('abort', abort);
    const timer = setTimeout(abort, 5000);
    try {
      const response = await this.transport(
        `${this.baseUrl.replace(/\/$/, '')}/api/v1/snapshot`,
        { signal: controller.signal, headers: { Accept: 'application/json' } },
      );
      if (!response.ok) {
        throw new Error(`Service indisponible (HTTP ${response.status})`);
      }
      return parseSnapshot(await response.json());
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
    }
  }
}
export const demoRepository = new MockStockRepository();
