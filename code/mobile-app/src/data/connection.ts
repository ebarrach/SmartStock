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
  if (!web && parsed.protocol === 'http:') {
    const octets = parsed.hostname.split('.').map(Number);
    const local =
      /^\d{1,3}(\.\d{1,3}){3}$/.test(parsed.hostname) &&
      octets.every(n => Number.isInteger(n) && n >= 0 && n <= 255) &&
      (octets[0] === 10 ||
        octets[0] === 127 ||
        (octets[0] === 192 && octets[1] === 168) ||
        (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31));
    if (!local)
      throw Error(
        'HTTP réservé aux IPv4 privées locales ou loopback ; utilisez HTTPS pour une autre destination.',
      );
  }
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
