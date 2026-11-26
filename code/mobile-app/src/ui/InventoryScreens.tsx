import React, { useState } from 'react';
import { Text, View } from 'react-native';
import { Thermometer, Droplets, Fan, Package } from './icons';
import { Snapshot, Batch } from '../data/repository';
import { Action, Card, Field, Message, Title } from './components';
import { theme } from './theme';
export function cabinetData(snapshot: Snapshot | null) {
  const batches = snapshot?.batches.filter(b => b.location === 'cabinet') ?? [];
  const ids = new Set(batches.map(b => b.id));
  const bagIds = new Set(
    snapshot?.batches.filter(b => b.location === 'bag').map(b => b.id),
  );
  const productIds = new Set(batches.map(b => b.productId));
  return {
    batches,
    movements: snapshot?.movements.filter(m => ids.has(m.batchId)) ?? [],
    alerts:
      snapshot?.alerts.filter(
        a =>
          !bagIds.has(a.subjectId) &&
          !a.subjectId.endsWith(':bag') &&
          (ids.has(a.subjectId) ||
            productIds.has(a.subjectId.replace(/:cabinet$/, '')) ||
            !a.subjectId.includes(':')),
      ) ?? [],
  };
}
export function StockScreen({
  snapshot,
  wide,
  onDetail,
  empty = false,
}: {
  snapshot: Snapshot | null;
  wide: boolean;
  onDetail: (b: Batch) => void;
  empty?: boolean;
}) {
  const [query, setQuery] = useState(''),
    [onlyLow, setOnlyLow] = useState(false);
  if (!snapshot)
    return <Message text="Aucune donnée de stock reçue pour le moment." />;
  const cabinetBatches = cabinetData(snapshot).batches;
  const totals = new Map<string, number>();
  cabinetBatches.forEach(b =>
    totals.set(b.productId, (totals.get(b.productId) ?? 0) + b.quantity),
  );
  const rows = (empty ? [] : cabinetBatches).filter(b => {
    const p = snapshot?.products.find(v => v.id === b.productId);
    return (
      `${p?.name} ${b.id}`.toLowerCase().includes(query.toLowerCase()) &&
      (!onlyLow || (totals.get(b.productId) ?? 0) < (p?.minStock ?? 0))
    );
  });
  return (
    <>
      <Field
        label="Rechercher un produit ou un lot"
        value={query}
        onChangeText={setQuery}
      />
      <Action
        secondary
        label={onlyLow ? 'Afficher tous les lots' : 'Stock faible uniquement'}
        onPress={() => setOnlyLow(!onlyLow)}
      />
      {wide && (
        <View style={{ flexDirection: 'row', padding: 16 }}>
          {['Produit / lot', 'Quantité', 'Péremption'].map(t => (
            <Text key={t} style={{ flex: 1, fontWeight: '700' }}>
              {t}
            </Text>
          ))}
        </View>
      )}
      {rows.length === 0 ? (
        <Card>
          <Message text="Aucun produit ne correspond à cette sélection." />
        </Card>
      ) : (
        rows.map(b => (
          <Card key={b.id}>
            <View style={{ flexDirection: wide ? 'row' : 'column', gap: 12 }}>
              <View style={{ flex: 1 }}>
                <Text
                  style={{
                    color: theme.colors.ink,
                    fontSize: 18,
                    fontWeight: '700',
                  }}
                >
                  {snapshot?.products.find(p => p.id === b.productId)?.name}
                </Text>
                <Message text={`Lot ${b.id}`} />
              </View>
              <Text
                style={{
                  flex: 1,
                  color: theme.colors.navigation,
                  fontWeight: '700',
                }}
              >
                {b.quantity} unités
              </Text>
              <Text style={{ flex: 1 }}>Péremption : {b.expiresOn}</Text>
            </View>
            <Action
              secondary
              label={`Détail ${b.id}`}
              onPress={() => onDetail(b)}
            />
          </Card>
        ))
      )}
    </>
  );
}
export function Dashboard({
  snapshot,
  columns,
}: {
  snapshot: Snapshot | null;
  columns: number;
}) {
  if (!snapshot)
    return (
      <Card>
        <Message text="Aucune donnée reçue pour le moment." />
      </Card>
    );
  const data = cabinetData(snapshot);
  const indicators = [
    {
      label: 'Unités en stock',
      value: String(data.batches.reduce((n, b) => n + b.quantity, 0)),
      icon: Package,
    },
    {
      label: 'Température',
      kind: 'temperature.measured',
      unit: '°C',
      icon: Thermometer,
    },
    { label: 'Humidité', kind: 'humidity.measured', unit: '%', icon: Droplets },
    { label: 'Ventilation', kind: 'fan.changed', unit: '%', icon: Fan },
  ];
  return (
    <>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12 }}>
        {indicators.map(item => {
          const reading = snapshot?.readings
            .filter(r => r.kind === item.kind)
            .at(-1);
          const Icon = item.icon;
          return (
            <View
              key={item.label}
              style={{
                width: columns === 1 ? '100%' : columns === 2 ? '48%' : '23%',
              }}
            >
              <Card>
                <Icon size={24} color={theme.colors.teal} />
                <Text>{item.label}</Text>
                <Title>
                  {item.value ??
                    (reading ? `${reading.value} ${item.unit}` : '—')}
                </Title>
                <Message
                  text={
                    reading
                      ? `Mesure : ${new Date(
                          reading.observedAt,
                        ).toLocaleString()}`
                      : item.kind
                      ? 'Mesure indisponible'
                      : `${data.batches.length} lots dans l’armoire`
                  }
                />
              </Card>
            </View>
          );
        })}
      </View>
      <Card>
        <Title>Votre armoire, en un regard</Title>
        <Message
          text={`${data.alerts.length} alertes · ${data.movements.length} mouvements enregistrés`}
        />
        <Message text="Les indicateurs affichent les dernières données reçues. La date de chaque mesure permet d’en vérifier la fraîcheur." />
      </Card>
    </>
  );
}
export function DetailScreen({
  snapshot,
  batchId,
  onBack,
}: {
  snapshot: Snapshot | null;
  batchId: string;
  onBack: () => void;
}) {
  const b = cabinetData(snapshot).batches.find(v => v.id === batchId);
  const p = snapshot?.products.find(v => v.id === b?.productId);
  return (
    <>
      <Action secondary label="Retour au stock" onPress={onBack} />
      <Card>
        {b ? (
          <>
            <Title>{p?.name}</Title>
            <Text>Lot : {b.id}</Text>
            <Text>Stock : {b.quantity}</Text>
            <Text>Péremption : {b.expiresOn}</Text>
            <Text>DataMatrix : {p?.dataMatrix}</Text>
            <Text>Seuil de stock : {p?.minStock}</Text>
            <Message text="Les corrections de stock sont disponibles via l’API ; aucune correction fictive n’est envoyée depuis cet écran." />
          </>
        ) : (
          <Message text="Ce lot n’est plus disponible." />
        )}
      </Card>
    </>
  );
}
export function ActivityScreen({
  snapshot,
  alerts,
}: {
  snapshot: Snapshot | null;
  alerts: boolean;
}) {
  if (!snapshot) return <Message text="Aucune donnée reçue pour le moment." />;
  const data = cabinetData(snapshot);
  return alerts ? (
    <>
      {data.alerts.length === 0 && (
        <Message text="Aucune alerte pour l’armoire." />
      )}
      {data.alerts.map((a, i) => (
        <Card key={`${a.code}-${i}`}>
          <Text style={{ color: theme.colors.red, fontWeight: '700' }}>
            {a.code.replace(/_/g, ' ')}
          </Text>
          <Text>{a.message}</Text>
          <Message text={a.subjectId} />
        </Card>
      ))}
    </>
  ) : (
    <>
      {data.movements.length === 0 && (
        <Message text="Aucun mouvement enregistré." />
      )}
      {[...data.movements].reverse().map(m => (
        <Card key={m.id}>
          <Text style={{ fontWeight: '700' }}>
            {m.batchId} · {m.delta > 0 ? '+' : ''}
            {m.delta} unités
          </Text>
          <Text>Stock après opération : {m.quantityAfter}</Text>
          {m.serial ? <Text>Série : {m.serial}</Text> : null}
          {m.initialPresenceUnknown ? (
            <Text>Présence initiale inconnue</Text>
          ) : null}
          <Text>
            {(
              {
                consumption: 'Consommation',
                replenishment: 'Réapprovisionnement',
                correction: 'Correction',
                loss: 'Perte',
                expiry: 'Péremption',
              } as Record<string, string>
            )[m.kind ?? ''] ?? 'Ancien mouvement non classé'}
          </Text>
          <Message
            text={`Origine : ${m.source ?? 'inconnue'}${
              m.reason ? ' · ' + m.reason : ''
            }`}
          />
          <Message
            text={new Date(m.occurredAt).toLocaleString(undefined, {
              timeZoneName: 'short',
            })}
          />
        </Card>
      ))}
    </>
  );
}
