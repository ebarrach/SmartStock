import React, { useEffect, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import Svg, { Rect, Defs, LinearGradient, Stop } from './svg';
import { StockRepository } from '../data/repository';
import { AnalysisReport } from '../data/analysis';
import { Action, Card, Field, Loading, Message, Title } from './components';
import { theme } from './theme';

export function GradientSurface({ children }: React.PropsWithChildren) {
  return (
    <View
      style={{
        padding: 20,
        borderRadius: 16,
        overflow: 'hidden',
        gap: 12,
        backgroundColor: theme.colors.navigation,
      }}
    >
      <Svg
        pointerEvents="none"
        accessible={false}
        style={{
          position: 'absolute',
          top: 0,
          left: 0,
          width: '100%',
          height: '100%',
        }}
      >
        <Defs>
          <LinearGradient id="surfaceGradient" x1="0" y1="0" x2="1" y2="1">
            <Stop offset="0" stopColor="#073646" />
            <Stop offset="1" stopColor="#008d81" />
          </LinearGradient>
        </Defs>
        <Rect width="100%" height="100%" fill="url(#surfaceGradient)" />
      </Svg>
      {children}
    </View>
  );
}
const display = (value: number | null | undefined) =>
  value === null || value === undefined
    ? 'Sans données'
    : Number(value.toFixed(2)).toLocaleString();
function Bars({
  points,
  unit,
}: {
  points: { label: string; value: number | null; detail: string }[];
  unit: string;
}) {
  const [selected, setSelected] = useState(0);
  const current = Math.min(selected, Math.max(0, points.length - 1));
  const max = Math.max(1, ...points.map(p => p.value ?? 0));
  return (
    <>
      <View
        style={{
          flexDirection: 'row',
          gap: 2,
          height: 155,
          alignItems: 'flex-end',
        }}
      >
        {points.map((p, i) => (
          <Pressable
            key={p.label}
            onPress={() => setSelected(i)}
            onHoverIn={() => setSelected(i)}
            accessibilityRole="button"
            accessibilityLabel={`${p.label} : ${display(p.value)} ${unit}`}
            style={{ flex: 1, minWidth: 0, alignItems: 'center' }}
          >
            <Svg width="100%" height={130} accessible={false}>
              <Rect
                x="10%"
                width="80%"
                y={
                  p.value === null
                    ? 2
                    : 128 - Math.max(2, (p.value / max) * 120)
                }
                height={
                  p.value === null ? 126 : Math.max(2, (p.value / max) * 120)
                }
                rx={3}
                fill={
                  p.value === null
                    ? '#edf1f5'
                    : i === current
                    ? theme.colors.navigation
                    : theme.colors.teal
                }
                stroke={p.value === null ? theme.colors.border : 'none'}
              />
            </Svg>
            <Text style={{ fontSize: 10, color: theme.colors.muted }}>
              {points.length > 12
                ? i % 5 === 0
                  ? p.label.slice(-2)
                  : ''
                : p.label.slice(5)}
            </Text>
          </Pressable>
        ))}
      </View>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <Action
          secondary
          label="Précédent"
          disabled={current === 0}
          onPress={() => setSelected(current - 1)}
        />
        <Action
          secondary
          label="Suivant"
          disabled={current >= points.length - 1}
          onPress={() => setSelected(current + 1)}
        />
      </View>
      {points[current] && (
        <Message
          text={`${points[current].label} : ${display(
            points[current].value,
          )} ${unit}. ${points[current].detail}`}
        />
      )}
      <Message text="Turquoise : valeur mesurée/enregistrée. Gris : absence de données, jamais assimilée à zéro. Touchez une barre ou utilisez Précédent/Suivant." />
    </>
  );
}
export function AnalysisScreen({
  repository,
  initialProduct = '',
  suggestions = false,
}: {
  repository: StockRepository;
  initialProduct?: string;
  suggestions?: boolean;
}) {
  const [months, setMonths] = useState(6),
    [report, setReport] = useState<AnalysisReport | null>(null),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(false),
    [refresh, setRefresh] = useState(0),
    [productId, setProductId] = useState(initialProduct),
    [picker, setPicker] = useState(false),
    [search, setSearch] = useState(''),
    [kind, setKind] = useState('consumption'),
    [sensorMonth, setSensorMonth] = useState('');
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    setReport(null);
    if (!repository.analyse) {
      setError('Analyse indisponible pour cette source.');
      setLoading(false);
      return () => controller.abort();
    }
    repository
      .analyse(months, controller.signal)
      .then(value => {
        if (active) setReport(value);
      })
      .catch(e => {
        if (active)
          setError(e instanceof Error ? e.message : 'Analyse indisponible');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [repository, months, refresh]);
  const product =
    report?.products.find(p => p.productId === productId) ??
    report?.products[0];
  const sensor =
    kind === 'temperature.measured' || kind === 'humidity.measured';
  const availableMonths = [
    ...new Set(report?.sensors.map(d => d.day.slice(0, 7)) ?? []),
  ];
  const selectedMonth = availableMonths.includes(sensorMonth)
    ? sensorMonth
    : report?.to.slice(0, 7) || '';
  const days =
    report?.sensors.filter(
      d => d.kind === kind && d.day.startsWith(selectedMonth),
    ) ?? [];
  const points = sensor
    ? days.map(d => ({
        label: d.day,
        value: d.mean,
        detail: `Min ${display(d.min)}, max ${display(d.max)}, ${
          d.count
        } mesures. Moyenne des observations disponibles, pas de remplissage des jours manquants.`,
      }))
    : product?.months.map(m => ({
        label: m.month,
        value:
          kind === 'stock'
            ? m.closingStock
            : kind === 'entries'
            ? m.entries
            : kind === 'exits'
            ? m.exits
            : m.consumption,
        detail: m.complete
          ? 'Mois complet observé.'
          : 'Mois partiel ou couverture insuffisante : exclu de la prévision.',
      })) ?? [];
  return (
    <>
      <View style={{ flexDirection: 'row', gap: 8, flexWrap: 'wrap' }}>
        {[3, 6, 12].map(n => (
          <Action
            key={n}
            secondary={n !== months}
            label={`${n} mois`}
            onPress={() => setMonths(n)}
          />
        ))}
        <Action
          label="Actualiser les analyses"
          onPress={() => setRefresh(v => v + 1)}
          disabled={loading}
        />
      </View>
      {loading && <Loading />}
      {!!error && <Message error text={error} />}
      {report && (
        <>
          <Message
            text={`Calcul du ${new Date(
              report.calculatedAt,
            ).toLocaleString()} · Période ${report.from} → ${report.to}${
              report.exampleData ? ' · Jeu de démonstration' : ''
            }`}
          />
          {suggestions ? (
            <>
              <Message text="Aide à la décision : aucune commande ni modification automatique du stock." />
              {report.products.map(p => {
                const s = p.suggestion;
                return (
                  <Card key={p.productId}>
                    <Title>{p.name}</Title>
                    <Text
                      style={{
                        color: theme.colors.navigation,
                        fontWeight: '700',
                        fontSize: 20,
                      }}
                    >
                      {s.quantity > 0
                        ? `Réapprovisionnement suggéré : ${s.quantity} ${p.unit}`
                        : 'Aucun réapprovisionnement nécessaire'}
                    </Text>
                    <Text>
                      Stock utilisable : {s.stockUsed} {p.unit} · Couverture
                      estimée : {display(s.coverageDays)} jours
                    </Text>
                    <Text>
                      Consommation observée : {s.observedConsumption} {p.unit}{' '}
                      sur {s.observedDays} jours de mois complets.
                    </Text>
                    <Text>
                      Dernier mouvement enregistré :{' '}
                      {p.recordedThrough ?? 'aucun'}.
                    </Text>
                    <Text>
                      Consommation estimée : {display(s.dailyConsumption)}{' '}
                      {p.unit}/jour · Horizon : {s.horizonDays} jours.
                    </Text>
                    <Message text={s.explanation} />
                    <Text>
                      Délai : {s.leadDays} jours · Marge : {s.safetyDays} jours
                      · Conditionnement : {s.packSize} · Cible :{' '}
                      {s.targetQuantity} {p.unit}.
                    </Text>
                    <Message
                      text={`Besoin calculé : ${display(s.requiredStock)} ${
                        p.unit
                      }. Règle de repli : seuil minimal si l’historique ne permet pas une estimation.`}
                    />
                    <Message text={`Qualité : ${s.quality}. ${s.limits}`} />
                    {s.backtestMae !== null && (
                      <Message
                        text={`Écart absolu moyen rétrospectif : ${display(
                          s.backtestMae,
                        )} ${p.unit} sur ${
                          s.backtestSamples
                        } mois testés sans données futures.`}
                      />
                    )}
                  </Card>
                );
              })}
            </>
          ) : (
            <>
              <Action
                secondary
                label={`Produit : ${product?.name ?? 'Choisir'}`}
                onPress={() => setPicker(!picker)}
              />
              {picker && (
                <Card>
                  <Field
                    label="Filtrer les produits"
                    value={search}
                    onChangeText={setSearch}
                  />
                  {report.products
                    .filter(p =>
                      p.name.toLowerCase().includes(search.toLowerCase()),
                    )
                    .map(p => (
                      <Action
                        secondary
                        key={p.productId}
                        label={p.name}
                        onPress={() => {
                          setProductId(p.productId);
                          setPicker(false);
                        }}
                      />
                    ))}
                </Card>
              )}
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
                {[
                  ['consumption', 'Consommation'],
                  ['entries', 'Entrées'],
                  ['exits', 'Sorties'],
                  ['stock', 'Évolution du stock'],
                  ['temperature.measured', 'Température'],
                  ['humidity.measured', 'Humidité'],
                ].map(([id, label]) => (
                  <Action
                    secondary={id !== kind}
                    key={id}
                    label={label}
                    onPress={() => setKind(id)}
                  />
                ))}
              </View>
              {sensor && (
                <View
                  style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}
                >
                  {[...new Set(report.sensors.map(d => d.day.slice(0, 7)))].map(
                    m => (
                      <Action
                        secondary={m !== selectedMonth}
                        key={m}
                        label={m}
                        onPress={() => setSensorMonth(m)}
                      />
                    ),
                  )}
                </View>
              )}
              <Card>
                <Title>
                  {sensor
                    ? kind === 'temperature.measured'
                      ? 'Température quotidienne'
                      : 'Humidité quotidienne'
                    : product?.name ?? 'Aucun produit'}
                </Title>
                {!sensor && (
                  <Message
                    text={`Dernier mouvement enregistré : ${
                      product?.recordedThrough ?? 'aucun'
                    }. Les mois sans trace ne sont pas supposés à zéro.`}
                  />
                )}
                <Bars
                  points={points}
                  unit={
                    sensor
                      ? kind === 'temperature.measured'
                        ? '°C'
                        : '%'
                      : product?.unit ?? 'unités'
                  }
                />
              </Card>
            </>
          )}
        </>
      )}
    </>
  );
}
