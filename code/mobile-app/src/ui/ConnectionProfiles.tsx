import React, { useEffect, useState } from 'react';
import { Platform, Text } from 'react-native';
import storage from '../data/connectionStorage';
import { normalizeServer, saveConnection } from '../data/connection';
import { Action, Card, Field, Message, Title } from './components';
type Profile = { id: string; name: string; url: string };
const key = 'connection-profiles-v1';
export function ConnectionProfiles({
  activeUrl,
  select,
}: {
  activeUrl: string;
  select: (url: string) => void;
}) {
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [id, setId] = useState('');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    storage
      .getItem(key)
      .then(async raw => {
        if (raw) {
          const saved = JSON.parse(raw);
          if (
            !Array.isArray(saved) ||
            !saved.every(
              p =>
                p &&
                typeof p.id === 'string' &&
                typeof p.name === 'string' &&
                typeof p.url === 'string',
            )
          )
            throw Error('Profils invalides');
          saved.forEach(p => normalizeServer(p.url, Platform.OS === 'web'));
          setProfiles(saved);
        } else if (activeUrl) {
          const initial = [{ id: 'home', name: 'Maison', url: activeUrl }];
          await storage.setItem(key, JSON.stringify(initial));
          setProfiles(initial);
        }
      })
      .catch(() =>
        setMessage('Profils illisibles : aucune préférence écrasée.'),
      );
  }, [activeUrl]);
  async function persist(next: Profile[]) {
    await storage.setItem(key, JSON.stringify(next));
    setProfiles(next);
  }
  async function run(work: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    try {
      await work();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Connexion impossible');
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card>
      <Title>Profils de connexion</Title>
      <Text>Adresse active : {activeUrl || 'aucune'}</Text>
      <Message text="Préférences locales. Aucun changement de stock ni basculement automatique." />
      {profiles.map(p => (
        <Card key={p.id}>
          <Text>
            {p.name} · {p.url}
            {p.url === activeUrl ? ' · actif' : ''}
          </Text>
          <Action
            secondary
            disabled={busy}
            label={`Modifier ${p.name}`}
            onPress={() => {
              setId(p.id);
              setName(p.name);
              setUrl(p.url);
            }}
          />
          <Action
            disabled={busy}
            label={`Sélectionner ${p.name}`}
            onPress={() =>
              run(async () => {
                const pending = JSON.parse(
                  (await storage.getItem('native-scanner-pending-v1')) ??
                    'null',
                );
                if (pending && pending.baseUrl !== p.url)
                  throw Error(
                    `Opération en attente sur ${pending.baseUrl}. Reprenez-la dans Scanner avant de changer de serveur. Elle ne sera pas envoyée ailleurs.`,
                  );
                await saveConnection({ mode: 'api', url: p.url });
                select(p.url);
                setMessage(
                  `Profil sélectionné : ${p.name}. Aucune opération rejouée.`,
                );
              })
            }
          />
          <Action
            secondary
            disabled={busy}
            label={`Tester ${p.name}`}
            onPress={() =>
              run(async () => {
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 5000);
                try {
                  const response = await fetch(p.url + '/api/v1/health', {
                    signal: controller.signal,
                  });
                  const body = await response.json();
                  if (!response.ok || body.status !== 'ok')
                    throw Error(`Test échoué : HTTP ${response.status}`);
                  setMessage(
                    `${p.name} : serveur joignable. Ce test ne sélectionne pas le profil.`,
                  );
                } finally {
                  clearTimeout(timer);
                }
              })
            }
          />
          <Action
            secondary
            disabled={busy || p.url === activeUrl}
            label={`Supprimer ${p.name}`}
            onPress={() =>
              run(async () => {
                await persist(profiles.filter(v => v.id !== p.id));
                setMessage('Profil supprimé localement.');
              })
            }
          />
        </Card>
      ))}
      <Field label="Nom du profil" value={name} onChangeText={setName} />
      <Field label="URL du profil" value={url} onChangeText={setUrl} />
      <Action
        label={id ? 'Enregistrer le profil modifié' : 'Ajouter le profil'}
        disabled={busy}
        onPress={() =>
          run(async () => {
            if (!name.trim()) throw Error('Nom requis');
            const address = normalizeServer(url, Platform.OS === 'web');
            if (profiles.some(p => p.id !== id && p.url === address))
              throw Error('Cette adresse possède déjà un profil.');
            const value = {
              id: id || `${Date.now()}-${profiles.length}`,
              name: name.trim(),
              url: address,
            };
            await persist([...profiles.filter(p => p.id !== value.id), value]);
            setId('');
            setName('');
            setUrl('');
            setMessage('Profil mémorisé.');
          })
        }
      />
      {!!message && <Message text={message} />}
    </Card>
  );
}
