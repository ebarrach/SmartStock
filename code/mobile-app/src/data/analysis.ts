import type { Product } from './repository';
export type CatalogDefinition = {
  exampleData: boolean;
  entries: {
    product: Product;
    category: string;
    unit: string;
    targetQuantity: number;
    leadDays: number;
    packSize: number;
    coverageDays: number;
    safetyDays: number;
    storageConditions: string;
  }[];
};
export type MonthPoint = {
  month: string;
  complete: boolean;
  observed: boolean;
  consumption: number | null;
  entries: number | null;
  exits: number | null;
  closingStock: number | null;
  days: number;
  stockout: boolean;
};
export type Suggestion = {
  quantity: number;
  stockUsed: number;
  dailyConsumption: number | null;
  coverageDays: number | null;
  horizonDays: number;
  leadDays: number;
  safetyDays: number;
  packSize: number;
  targetQuantity: number;
  requiredStock: number;
  dailyVariation: number | null;
  observedDays: number;
  observedConsumption: number;
  method: string;
  quality: string;
  explanation: string;
  limits: string;
  backtestMae: number | null;
  backtestSamples: number;
};
export type ProductAnalysis = {
  recordedThrough: string | null;
  productId: string;
  name: string;
  unit: string;
  months: MonthPoint[];
  suggestion: Suggestion;
};
export type SensorDay = {
  day: string;
  kind: string;
  count: number;
  mean: number | null;
  min: number | null;
  max: number | null;
};
export type AnalysisReport = {
  calculatedAt: string;
  from: string;
  to: string;
  exampleData: boolean;
  products: ProductAnalysis[];
  sensors: SensorDay[];
};
export function parseAnalysis(value: unknown): AnalysisReport {
  if (!value || typeof value !== 'object') throw Error('Analyse API invalide');
  const r = value as AnalysisReport;
  const finite = (v: unknown) => typeof v === 'number' && Number.isFinite(v);
  const optional = (v: unknown) => v === null || finite(v);
  if (
    !Array.isArray(r.products) ||
    !Array.isArray(r.sensors) ||
    !Number.isFinite(Date.parse(r.calculatedAt)) ||
    !/^\d{4}-\d{2}-\d{2}$/.test(r.from) ||
    !/^\d{4}-\d{2}-\d{2}$/.test(r.to) ||
    typeof r.exampleData !== 'boolean'
  )
    throw Error('Enveloppe analyse invalide');
  for (const p of r.products) {
    if (
      !p ||
      typeof p.productId !== 'string' ||
      typeof p.name !== 'string' ||
      typeof p.unit !== 'string' ||
      !Array.isArray(p.months) ||
      !p.suggestion
    )
      throw Error('Produit analyse invalide');
    for (const m of p.months)
      if (
        !/^\d{4}-\d{2}$/.test(m.month) ||
        ![m.consumption, m.entries, m.exits, m.closingStock].every(optional) ||
        typeof m.complete !== 'boolean'
      )
        throw Error('Serie mensuelle invalide');
    const s = p.suggestion;
    if (
      !finite(s.quantity) ||
      s.quantity < 0 ||
      !Number.isInteger(s.quantity) ||
      !finite(s.stockUsed) ||
      s.stockUsed < 0 ||
      !finite(s.observedDays) ||
      ![
        s.horizonDays,
        s.observedConsumption,
        s.leadDays,
        s.safetyDays,
        s.packSize,
        s.targetQuantity,
        s.requiredStock,
        s.backtestSamples,
      ].every(v => finite(v) && v >= 0) ||
      !optional(s.dailyConsumption) ||
      !optional(s.dailyVariation) ||
      !optional(s.backtestMae) ||
      !optional(s.coverageDays) ||
      typeof s.method !== 'string' ||
      typeof s.quality !== 'string' ||
      typeof s.explanation !== 'string' ||
      typeof s.limits !== 'string'
    )
      throw Error('Suggestion invalide');
  }
  for (const d of r.sensors)
    if (
      !/^\d{4}-\d{2}-\d{2}$/.test(d.day) ||
      !finite(d.count) ||
      ![d.mean, d.min, d.max].every(optional)
    )
      throw Error('Serie capteur invalide');
  return r;
}
