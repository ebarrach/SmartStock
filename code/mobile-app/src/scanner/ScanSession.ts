import { decodeGS1, GS1, normalizeGTIN } from './gs1';
import { parseSnapshot, Movement } from '../data/repository';
export type PendingScan = {
  operationId: string;
  eventId: string;
  baseUrl: string;
  code: GS1;
  timestamp: string;
  direction: 'auto' | 'entry' | 'exit';
  mode?: 'test' | 'normal';
  envelope?: unknown;
  sent?: boolean;
  registration?: {
    name: string;
    productId: string;
    productCommandId: string;
    batchId: string;
    batchCommandId: string;
    sent?: boolean;
    done?: boolean;
  };
};
export type ScanState = {
  phase: 'ready' | 'working' | 'error' | 'waiting' | 'confirmed' | 'review';
  message: string;
  pending?: PendingScan;
  product?: string;
  movement?: Movement;
  choice?: boolean;
  uncertain?: boolean;
  issue?:
    | 'unknown_product'
    | 'ambiguous_product'
    | 'new_batch'
    | 'ambiguous_batch';
};
export interface ScanStorage {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
}
class ScanFailure extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly definitive = true,
  ) {
    super(message);
  }
}
const key = 'native-scanner-pending-v1';
let persistence: Promise<unknown> = Promise.resolve();
function serialize<T>(work: () => Promise<T>): Promise<T> {
  const result = persistence.catch(() => {}).then(work);
  persistence = result;
  return result;
}
export class ScanSession {
  state: ScanState = {
    phase: 'ready',
    message: 'Centrez un seul DataMatrix dans le cadre.',
  };
  locked = false;
  private busy = false;
  private storedId?: string;
  constructor(
    private storage: ScanStorage,
    private uuid: () => string,
    private change: (state: ScanState) => void,
    private confirmed: () => Promise<void>,
    private transport: typeof fetch = globalThis.fetch.bind(globalThis),
    private read: () => void = () => {},
  ) {}
  private emit(patch: Partial<ScanState>) {
    this.state = { ...this.state, ...patch };
    this.change(this.state);
  }
  private async save() {
    const value = JSON.stringify(this.state.pending ?? null);
    const operationId = this.state.pending?.operationId;
    await serialize(async () => {
      const current = JSON.parse(
        (await this.storage.getItem(key)) ?? 'null',
      ) as PendingScan | null;
      if (
        current &&
        current.operationId !== this.storedId &&
        current.operationId !== operationId
      )
        throw new ScanFailure(
          'operation_superseded',
          'Une autre opération est conservée : fermer et rouvrir le scanner pour la reprendre.',
        );
      if (!current && this.storedId && this.storedId === operationId)
        throw new ScanFailure(
          'operation_superseded',
          'Cette session a déjà été terminée ailleurs.',
        );
      await this.storage.setItem(key, value);
      this.storedId = operationId;
    });
  }
  private async clearPending() {
    const operationId = this.state.pending?.operationId;
    await serialize(async () => {
      const current = JSON.parse(
        (await this.storage.getItem(key)) ?? 'null',
      ) as PendingScan | null;
      if (!current || current.operationId === operationId)
        await this.storage.setItem(key, 'null');
    });
  }
  async restore() {
    const value = JSON.parse(
      (await serialize(() => this.storage.getItem(key))) ?? 'null',
    ) as PendingScan | null;
    if (value) {
      this.storedId = value.operationId;
      if (
        !/^[\da-f-]{36}$/i.test(value.operationId) ||
        !value.eventId ||
        !value.baseUrl ||
        !value.code?.raw
      )
        throw Error(
          'Opération locale illisible : ne pas créer de nouveau mouvement',
        );
      this.locked = true;
      this.emit({
        phase: 'error',
        pending: value,
        uncertain: true,
        message:
          'Opération conservée. Réessayez avec le même identifiant avant un nouveau scan.',
      });
    }
  }
  async detect(values: string[], baseUrl: string) {
    if (this.locked || this.busy) return;
    this.locked = true;
    const unique = [...new Set(values)];
    try {
      if (unique.length !== 1)
        throw Error(
          'Plusieurs DataMatrix visibles : isolez une seule boîte puis recommencez.',
        );
      const code = decodeGS1(unique[0]);
      try {
        this.read();
      } catch {
        /* Audio feedback must not block inventory operations. */
      }
      if (!baseUrl)
        throw Error(
          'Connectez SmartStock à son API dans Connexion. Aucun stock Démo local ne sera modifié.',
        );
      const pending: PendingScan = {
        operationId: this.uuid(),
        eventId: this.uuid(),
        baseUrl,
        code,
        timestamp: new Date().toISOString(),
        direction: 'auto',
      };
      this.emit({
        pending,
        phase: 'working',
        message: 'DataMatrix lu, vérification auprès du serveur…',
        uncertain: false,
      });
      await this.save();
      await this.submit();
    } catch (error) {
      this.emit({
        phase: 'error',
        message: error instanceof Error ? error.message : 'Lecture impossible',
      });
    }
  }
  private async request(
    path: string,
    body?: unknown,
    id?: string,
  ): Promise<any> {
    const pending = this.state.pending!;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await this.transport(
        pending.baseUrl.replace(/\/$/, '') + '/api/v1' + path,
        {
          signal: controller.signal,
          method: body === undefined ? 'GET' : 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(id ? { 'X-Command-Id': id } : {}),
          },
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        },
      );
      const data = await response.json();
      if (!response.ok)
        throw new ScanFailure(
          data.code ?? 'api_error',
          data.message ?? `Erreur API ${response.status}`,
          response.status < 500,
        );
      return data;
    } finally {
      clearTimeout(timer);
    }
  }
  private async finish(movement: Movement) {
    this.emit({
      phase: 'confirmed',
      movement,
      uncertain: false,
      message: `${
        movement.delta > 0 ? 'Ajout' : 'Retrait'
      } confirmé : ${Math.abs(movement.delta)} unité(s). Stock du lot : ${
        movement.quantityAfter
      }.`,
    });
    // Keep identity in memory until explicit rearm; remove only the pending disk record.
    await this.clearPending();
    await this.confirmed();
  }
  async submit(direction?: 'entry' | 'exit') {
    if (this.busy || !this.state.pending) return;
    if (
      this.state.pending.registration &&
      !this.state.pending.registration.done
    ) {
      await this.register();
      return;
    }
    this.busy = true;
    try {
      if (direction) {
        if (!this.state.choice || this.state.uncertain) return;
        this.emit({
          pending: {
            ...this.state.pending,
            operationId: this.uuid(),
            eventId: this.uuid(),
            direction,
            sent: false,
          },
          choice: false,
        });
      }
      const pending = this.state.pending!;
      this.emit({ phase: 'working', message: 'Traitement du scan…' });
      await this.save();
      const snapshot = parseSnapshot(await this.request('/snapshot'));
      const products = snapshot.products.filter(
        p => normalizeGTIN(p.dataMatrix) === normalizeGTIN(pending.code.gtin!),
      );
      const product = products.length === 1 ? products[0] : undefined;
      this.emit({
        product:
          products.length > 1
            ? 'Plusieurs fiches correspondent'
            : product?.name ?? 'Produit non enregistré',
      });
      if (!pending.code.lot || !pending.code.expiration)
        throw new ScanFailure(
          'incomplete_code',
          'Lot ou péremption absent : aucune opération automatique.',
        );
      if (!pending.mode) {
        const mode = await this.request('/scan-only-test');
        pending.mode = mode.enabled && mode.allowed ? 'test' : 'normal';
        await this.save();
      }
      if (products.length > 1)
        throw new ScanFailure(
          'ambiguous_product',
          'Plusieurs fiches correspondent à ce GTIN. Aucun enregistrement ni mouvement automatique.',
        );
      if (!product)
        throw new ScanFailure(
          'unknown_product',
          'Produit absent du catalogue actif. Enregistrez sa fiche en confirmant son nom.',
        );
      const matchingBatches = snapshot.batches.filter(
        b =>
          b.productId === product.id &&
          b.location === 'cabinet' &&
          b.expiresOn === pending.code.expiration &&
          (b as any).lotCode === pending.code.lot,
      );
      if (matchingBatches.length > 1)
        throw new ScanFailure(
          'ambiguous_batch',
          'Plusieurs lots correspondent. Aucun nouveau lot ni mouvement automatique.',
        );
      if (matchingBatches.length === 0)
        throw new ScanFailure(
          'new_batch',
          `Produit reconnu : ${product.name}. Ce nouveau lot doit être enregistré, sans recréer le produit.`,
        );
      // A prior timeout can hide a committed movement. Reconcile before retry.
      if (pending.sent) {
        try {
          const known = await this.request(
            '/operations/' + pending.operationId,
          );
          if (known.status === 'confirmed' && known.movement) {
            await this.finish(known.movement);
            return;
          }
        } catch (error) {
          if (
            !(error instanceof ScanFailure) ||
            error.code !== 'operation_unknown'
          )
            throw error;
        }
      }
      if (pending.mode === 'test') {
        pending.sent = true;
        await this.save();
        const reply = await this.request(
          '/scan-only-test',
          {
            gtin: pending.code.gtin,
            lot: pending.code.lot,
            expiration: pending.code.expiration,
            ...(pending.code.serial ? { serial: pending.code.serial } : {}),
            direction: pending.direction,
          },
          pending.operationId,
        );
        if (!reply.movement || reply.movement.eventId !== pending.operationId)
          throw Error(
            'Réponse sans mouvement confirmé : vérifier le statut, ne pas scanner à nouveau.',
          );
        await this.finish(reply.movement);
      } else {
        if (!product)
          throw new ScanFailure(
            'unknown_product',
            'Produit inconnu : aucun mouvement.',
          );
        const batches = snapshot.batches.filter(
          b =>
            b.productId === product.id &&
            b.location === 'cabinet' &&
            b.expiresOn === pending.code.expiration &&
            (b as any).lotCode === pending.code.lot,
        );
        if (batches.length !== 1)
          throw new ScanFailure(
            'ambiguous_batch',
            'Lot physique absent ou ambigu : aucun mouvement.',
          );
        if (!pending.envelope)
          pending.envelope = {
            v: 1,
            id: pending.eventId,
            type: 'datamatrix.detected',
            src: 'capture-' + pending.operationId.replace(/-/g, ''),
            seq: 1,
            ts: pending.timestamp,
            corr: null,
            data: {
              batchId: batches[0].id,
              operationId: pending.operationId,
              code: pending.code.gtin,
              lot: pending.code.lot,
              expiration: pending.code.expiration,
              ...(pending.code.serial ? { serial: pending.code.serial } : {}),
            },
          };
        pending.sent = true;
        await this.save();
        await this.request('/events', pending.envelope);
        const result = await this.request('/operations/' + pending.operationId);
        if (result.status === 'confirmed' && result.movement)
          await this.finish(result.movement);
        else
          this.emit({
            phase: 'waiting',
            uncertain: false,
            message:
              'Scan reçu durablement, mais aucun mouvement confirmé. Mode normal : poids calibré et même identifiant d’opération requis.',
          });
      }
    } catch (error) {
      const rejected = error instanceof ScanFailure && error.definitive;
      this.emit({
        phase: 'error',
        uncertain: !rejected && !!this.state.pending?.sent,
        choice:
          rejected &&
          this.state.pending?.mode === 'test' &&
          ['presence_unknown', 'unknown_serial'].includes(error.code),
        issue:
          rejected &&
          [
            'unknown_product',
            'ambiguous_product',
            'new_batch',
            'ambiguous_batch',
          ].includes(error.code)
            ? (error.code as ScanState['issue'])
            : undefined,
        message: rejected
          ? error.message
          : 'Réseau indisponible ou délai dépassé. Réessayez la même opération ; aucun remplacement par des données fictives.',
      });
    } finally {
      this.busy = false;
    }
  }
  async register(name?: string) {
    if (this.busy || !this.state.pending) return;
    this.busy = true;
    const pending = this.state.pending;
    try {
      if (!pending.code.gtin || !pending.code.lot || !pending.code.expiration)
        throw new ScanFailure(
          'incomplete_code',
          'GTIN, lot et péremption doivent être confirmés avant enregistrement.',
        );
      this.emit({
        phase: 'working',
        message: 'Enregistrement de la fiche et du lot, sans mouvement…',
      });
      let snapshot = parseSnapshot(await this.request('/snapshot'));
      let products = snapshot.products.filter(
        p => normalizeGTIN(p.dataMatrix) === normalizeGTIN(pending.code.gtin!),
      );
      if (products.length > 1)
        throw new ScanFailure(
          'ambiguous_product',
          'Plusieurs produits correspondent. Aucun doublon créé.',
        );
      let product = products[0];
      if (!pending.registration) {
        const confirmedName = product?.name ?? name?.trim();
        if (!confirmedName)
          throw new ScanFailure(
            'name_required',
            'Saisissez le nom réel indiqué sur la boîte.',
          );
        pending.registration = {
          name: confirmedName,
          productId: product?.id ?? 'gtin-' + pending.code.gtin,
          productCommandId: this.uuid(),
          batchId: 'lot-' + this.uuid(),
          batchCommandId: this.uuid(),
        };
        await this.save();
      }
      const registration = pending.registration;
      if (!product) {
        registration.sent = true;
        await this.save();
        try {
          await this.request(
            '/products',
            {
              id: registration.productId,
              name: registration.name,
              dataMatrix: pending.code.gtin,
              unitWeightGrams: null,
              minStock: 0,
            },
            registration.productCommandId,
          );
        } catch (error) {
          if (
            !(error instanceof ScanFailure) ||
            error.code !== 'product_exists'
          )
            throw error;
        }
        snapshot = parseSnapshot(await this.request('/snapshot'));
        products = snapshot.products.filter(
          p =>
            normalizeGTIN(p.dataMatrix) === normalizeGTIN(pending.code.gtin!),
        );
        if (products.length !== 1)
          throw new ScanFailure(
            'ambiguous_product',
            'Enregistrement concurrent ou identifiant occupé : vérifiez les fiches existantes.',
          );
        product = products[0];
      }
      this.emit({ product: product.name });
      const batches = snapshot.batches.filter(
        b =>
          b.productId === product.id &&
          b.location === 'cabinet' &&
          b.expiresOn === pending.code.expiration &&
          (b as any).lotCode === pending.code.lot,
      );
      if (batches.length > 1)
        throw new ScanFailure(
          'ambiguous_batch',
          'Plusieurs lots correspondent : aucun lot supplémentaire créé.',
        );
      if (!batches.length) {
        registration.sent = true;
        await this.save();
        await this.request(
          '/batches/empty',
          {
            id: registration.batchId,
            productId: product.id,
            location: 'cabinet',
            quantity: 0,
            expiresOn: pending.code.expiration,
            lotCode: pending.code.lot,
            serialInventoryStatus: 'inventoried',
            traceabilityNote:
              'Nouveau lot enregistré vide après confirmation du DataMatrix ; aucune présence individuelle déduite.',
          },
          registration.batchCommandId,
        );
      }
      registration.done = true;
      await this.save();
      this.emit({
        phase: 'review',
        issue: undefined,
        choice: pending.mode === 'test',
        uncertain: false,
        message:
          pending.mode === 'test'
            ? 'Fiche et lot enregistrés. Aucun mouvement effectué. Confirmez explicitement l’ajout de cette boîte en mode test sans balance.'
            : 'Fiche et lot enregistrés, sans mouvement. La validation par poids exige une masse unitaire réellement renseignée.',
      });
      await this.confirmed();
    } catch (error) {
      const rejected = error instanceof ScanFailure && error.definitive;
      this.emit({
        phase: 'error',
        uncertain: !rejected && !!pending.registration?.sent,
        issue:
          rejected &&
          ['ambiguous_product', 'ambiguous_batch'].includes(error.code)
            ? (error.code as ScanState['issue'])
            : this.state.issue,
        message: rejected
          ? error.message
          : 'Enregistrement interrompu : réessayez avec les mêmes identifiants. Ne recréez pas une autre fiche.',
      });
    } finally {
      this.busy = false;
    }
  }
  async reset() {
    if (this.busy || this.state.uncertain) return;
    await this.clearPending();
    this.storedId = undefined;
    this.state = {
      phase: 'ready',
      message: 'Nouvelle opération : centrez un seul DataMatrix.',
    };
    this.locked = false;
    this.change(this.state);
  }
}
