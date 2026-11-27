import {
  loadConnection,
  normalizeServer,
  saveConnection,
} from '../src/data/connection';
import storage from '../src/data/connectionStorage';

test('accepte de nouvelles IPv4 locales sans compilation et refuse HTTP public', () => {
  for (const url of [
    'http://10.42.8.91:18119',
    'http://172.22.5.13:18119',
    'http://192.168.77.23:18119',
  ])
    expect(normalizeServer(url, false)).toBe(url);
  for (const url of [
    'http://8.8.8.8:18119',
    'http://example.com',
    'http://172.32.0.1',
    'http://192.169.0.1',
  ])
    expect(() => normalizeServer(url, false)).toThrow();
  expect(normalizeServer('https://example.com', false)).toBe(
    'https://example.com',
  );
});
test('mémorise serveur Wi-Fi et choix API/Démo sans données de stock', async () => {
  expect(await loadConnection(false)).toBeNull();
  await saveConnection({ mode: 'api', url: 'http://192.168.0.3:18099' });
  expect(await loadConnection(false)).toEqual({
    mode: 'api',
    url: 'http://192.168.0.3:18099',
  });
  await saveConnection({ mode: 'demo', url: 'http://192.168.0.3:18099' });
  expect(await loadConnection(false)).toEqual({
    mode: 'demo',
    url: 'http://192.168.0.3:18099',
  });
});
test('refuse les configurations invalides plutôt que revenir silencieusement en Démo', async () => {
  await storage.setItem('connection-v1', 'broken');
  await expect(loadConnection(false)).rejects.toThrow();
  await storage.setItem(
    'connection-v1',
    JSON.stringify({ mode: 'api', url: '' }),
  );
  await expect(loadConnection(false)).rejects.toThrow();
  for (const url of [
    'ftp://server',
    'https://user:pass@server',
    'https://server/?token=x',
    'https://server/path',
    '/agent',
  ])
    expect(() => normalizeServer(url, false)).toThrow();
  expect(normalizeServer('/agent', true)).toBe('/agent');
  expect(normalizeServer(' https://server/ ', false)).toBe('https://server');
});
