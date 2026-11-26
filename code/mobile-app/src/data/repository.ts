import demo from '../../../shared/api/examples/demo-snapshot.json';
import demoCatalog from '../../../shared/api/examples/demo-catalog.json';
import analyses from '../../../shared/api/examples/demo-analytics.json';
import { AnalysisReport, parseAnalysis, CatalogDefinition } from './analysis';

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
  productId?: string;
  kind?: string;
  source?: string;
  reason?: string;
  serial?: string | null;
  initialPresenceUnknown?: boolean;
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
  catalogue?(): Promise<CatalogDefinition>;
  analyse?(months: number, signal?: AbortSignal): Promise<AnalysisReport>;
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
      Number(m.quantityAfter) < 0 ||
      (m.serial != null &&
        (typeof m.serial !== 'string' ||
          m.serial.length < 1 ||
          m.serial.length > 20)) ||
      (m.initialPresenceUnknown !== undefined &&
        typeof m.initialPresenceUnknown !== 'boolean')
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

function parseCatalogue(value: unknown): CatalogDefinition {
  const catalog = value as CatalogDefinition;
  if (
    !catalog ||
    typeof catalog.exampleData !== 'boolean' ||
    !Array.isArray(catalog.entries)
  )
    throw Error('Catalogue API invalide');
  parseSnapshot({
    products: catalog.entries.map(e => e.product),
    batches: [],
    movements: [],
    readings: [],
    alerts: [],
    simulated: catalog.exampleData,
  });
  for (const e of catalog.entries)
    if (
      typeof e.category !== 'string' ||
      typeof e.unit !== 'string' ||
      ![
        e.targetQuantity,
        e.leadDays,
        e.packSize,
        e.coverageDays,
        e.safetyDays,
      ].every(v => Number.isInteger(v) && v >= 0) ||
      e.packSize === 0 ||
      e.coverageDays === 0
    )
      throw Error('Parametres catalogue invalides');
  return catalog;
}
export class MockStockRepository implements StockRepository {
  readonly mode = 'simulated' as const;
  async catalogue(): Promise<CatalogDefinition> {
    return parseCatalogue(demoCatalog);
  }
  async analyse(months: number): Promise<AnalysisReport> {
    return parseAnalysis(analyses[String(months) as keyof typeof analyses]);
  }
  async load(): Promise<Snapshot> {
    return parseSnapshot(JSON.parse(JSON.stringify(demo)));
  }
}
export class ApiStockRepository implements StockRepository {
  readonly mode = 'api' as const;
  async catalogue(): Promise<CatalogDefinition> {
    return parseCatalogue(await this.request('/catalog'));
  }
  constructor(
    private readonly baseUrl: string,
    private readonly transport: typeof fetch = globalThis.fetch.bind(
      globalThis,
    ),
  ) {}
  async load(signal?: AbortSignal): Promise<Snapshot> {
    return parseSnapshot(await this.request('/snapshot', signal));
  }
  async analyse(months: number, signal?: AbortSignal): Promise<AnalysisReport> {
    return parseAnalysis(
      await this.request('/analytics?months=' + months, signal),
    );
  }
  private async request(path: string, signal?: AbortSignal): Promise<unknown> {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) {
      controller.abort();
    }
    signal?.addEventListener('abort', abort);
    const timer = setTimeout(abort, 5000);
    try {
      const response = await this.transport(
        `${this.baseUrl.replace(/\/$/, '')}/api/v1${path}`,
        { signal: controller.signal, headers: { Accept: 'application/json' } },
      );
      if (!response.ok) {
        throw new Error(`Service indisponible (HTTP ${response.status})`);
      }
      return await response.json();
    } catch (error) {
      if (controller.signal.aborted && !signal?.aborted)
        throw new Error(
          'Délai dépassé (5 s). Vérifiez le Wi-Fi et le serveur, puis réessayez.',
        );
      if (error instanceof Error && error.name === 'TypeError')
        throw new Error(
          'Serveur inaccessible. Vérifiez le Wi-Fi, l’adresse et le pare-feu.',
        );
      throw error;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
    }
  }
}
export const demoRepository = new MockStockRepository();
