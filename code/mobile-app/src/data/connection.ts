import storage from './connectionStorage';
export type Connection = { mode: 'api' | 'demo'; url: string };
const key = 'connection-v1';
export function normalizeServer(value: string, web: boolean): string {
  const trimmed = value.trim().replace(/\/$/, '');
  if (web && trimmed === '/agent') return trimmed;
  if (!/^https?:\/\//i.test(trimmed))
    throw Error(
      'Indiquez une URL HTTP/HTTPS complète ou /agent sur le navigateur.',
    );
  const parsed = new URL(trimmed);
  if (
    !['http:', 'https:'].includes(parsed.protocol) ||
    parsed.username ||
    parsed.password ||
    parsed.search ||
    parsed.hash ||
    !['', '/'].includes(parsed.pathname)
  )
    throw Error(
      'Adresse du serveur HTTP/HTTPS sans identifiants, chemin ni paramètres requise',
    );
  return trimmed;
}
export async function loadConnection(web: boolean): Promise<Connection | null> {
  const raw = await storage.getItem(key);
  if (raw === null) return null;
  const value = JSON.parse(raw);
  if (
    !value ||
    !['api', 'demo'].includes(value.mode) ||
    typeof value.url !== 'string' ||
    (value.mode === 'api' && !value.url)
  )
    throw Error('Configuration de connexion enregistrée invalide');
  return {
    mode: value.mode,
    url: value.url ? normalizeServer(value.url, web) : '',
  };
}
export async function saveConnection(value: Connection) {
  await storage.setItem(key, JSON.stringify(value));
}
