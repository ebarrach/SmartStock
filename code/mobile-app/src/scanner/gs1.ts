export type GS1 = Readonly<{
  raw: string;
  gtin?: string;
  lot?: string;
  expiration?: string;
  serial?: string;
}>;
const GS = '\u001d';
export function normalizeGTIN(value: string): string {
  return /^(?:\d{8}|\d{12}|\d{13}|\d{14})$/.test(value)
    ? value.padStart(14, '0')
    : value;
}
export function decodeGS1(raw: string): GS1 {
  if (!raw || raw.length > 2048) throw Error('DataMatrix vide ou trop long');
  let text = raw.replace(/^\]d2/i, '').replace(/[\r\n]+$/, '');
  if (text.startsWith(GS)) text = text.slice(1);
  const fields = new Map<string, string>();
  const put = (ai: string, value: string) => {
    if (fields.has(ai))
      throw Error(`Identifiant GS1 ${ai} dupliqué : code ambigu`);
    if (!value || [...value].some(character => character.charCodeAt(0) < 32))
      throw Error(`Valeur GS1 ${ai} invalide`);
    fields.set(ai, value);
  };
  if (text.startsWith('(')) {
    const matches = [...text.matchAll(/\((01|10|17|21)\)/g)];
    if (matches[0]?.index !== 0) throw Error('Structure GS1 non reconnue');
    matches.forEach((m, i) => {
      const value = text.slice(
        m.index! + 4,
        matches[i + 1]?.index ?? text.length,
      );
      put(m[1], value.endsWith(GS) ? value.slice(0, -1) : value);
    });
  } else {
    while (text.length) {
      const ai = text.slice(0, 2);
      text = text.slice(2);
      if (ai === '01' || ai === '17') {
        const width = ai === '01' ? 14 : 6;
        put(ai, text.slice(0, width));
        text = text.slice(width);
        if (text.startsWith(GS)) text = text.slice(1);
      } else if (ai === '10' || ai === '21') {
        const end = text.indexOf(GS);
        put(ai, end < 0 ? text : text.slice(0, end));
        text = end < 0 ? '' : text.slice(end + 1);
      } else
        throw Error(
          `AI GS1 ${ai} non pris en charge. Vérifier les séparateurs ASCII 29.`,
        );
    }
  }
  const gtin = fields.get('01'),
    lot = fields.get('10'),
    serial = fields.get('21');
  if (!gtin || !/^\d{14}$/.test(gtin))
    throw Error('GTIN GS1 : 14 chiffres requis');
  if ([lot, serial].some(v => v !== undefined && v.length > 20))
    throw Error('Lot ou série GS1 trop long');
  let expiration: string | undefined;
  const expiry = fields.get('17');
  if (expiry !== undefined) {
    if (!/^\d{6}$/.test(expiry)) throw Error('Péremption GS1 invalide');
    const year = 2000 + Number(expiry.slice(0, 2)),
      month = Number(expiry.slice(2, 4));
    const day =
      Number(expiry.slice(4)) ||
      new Date(Date.UTC(year, month, 0)).getUTCDate();
    const date = new Date(Date.UTC(year, month - 1, day));
    if (
      date.getUTCFullYear() !== year ||
      date.getUTCMonth() !== month - 1 ||
      date.getUTCDate() !== day
    )
      throw Error('Date de péremption impossible');
    expiration = date.toISOString().slice(0, 10);
  }
  return { raw, gtin, lot, serial, expiration };
}
