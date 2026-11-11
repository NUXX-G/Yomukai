// Text as a voice should say it (F6.1): numbers, ordinals, percentages, abbreviations and pauses, in Spanish and
// English. The same rules as shared/…/data/speech/SpeechText.kt, both checked against
// shared/src/test/resources/speech_text.json.

const UNITS_ES = ['cero', 'uno', 'dos', 'tres', 'cuatro', 'cinco', 'seis', 'siete', 'ocho', 'nueve', 'diez', 'once', 'doce', 'trece', 'catorce', 'quince',
  'dieciséis', 'diecisiete', 'dieciocho', 'diecinueve', 'veinte', 'veintiuno', 'veintidós', 'veintitrés', 'veinticuatro', 'veinticinco',
  'veintiséis', 'veintisiete', 'veintiocho', 'veintinueve'];
const TENS_ES = ['', '', 'veinte', 'treinta', 'cuarenta', 'cincuenta', 'sesenta', 'setenta', 'ochenta', 'noventa'];
const HUNDREDS_ES = ['', 'ciento', 'doscientos', 'trescientos', 'cuatrocientos', 'quinientos', 'seiscientos', 'setecientos', 'ochocientos', 'novecientos'];
const ORDINALS_ES = ['', 'primero', 'segundo', 'tercero', 'cuarto', 'quinto', 'sexto', 'séptimo', 'octavo', 'noveno', 'décimo'];
const UNITS_EN = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen',
  'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen'];
const TENS_EN = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'];

const apocope = (w) => w.replace(/veintiuno$/, 'veintiún').replace(/uno$/, 'un');

export function cardinalEs(n) {
  if (n < 0) return 'menos ' + cardinalEs(-n);
  if (n < 30) return UNITS_ES[n];
  if (n < 100) return TENS_ES[Math.floor(n / 10)] + (n % 10 ? ' y ' + UNITS_ES[n % 10] : '');
  if (n === 100) return 'cien';
  if (n < 1000) return HUNDREDS_ES[Math.floor(n / 100)] + (n % 100 ? ' ' + cardinalEs(n % 100) : '');
  if (n < 1e6) {
    const k = Math.floor(n / 1000);
    return (k === 1 ? 'mil' : apocope(cardinalEs(k)) + ' mil') + (n % 1000 ? ' ' + cardinalEs(n % 1000) : '');
  }
  if (n < 1e12) {
    const m = Math.floor(n / 1e6);
    return (m === 1 ? 'un millón' : apocope(cardinalEs(m)) + ' millones') + (n % 1e6 ? ' ' + cardinalEs(n % 1e6) : '');
  }
  return String(n);
}

export function cardinalEn(n) {
  if (n < 0) return 'minus ' + cardinalEn(-n);
  if (n < 20) return UNITS_EN[n];
  if (n < 100) return TENS_EN[Math.floor(n / 10)] + (n % 10 ? '-' + UNITS_EN[n % 10] : '');
  if (n < 1000) return UNITS_EN[Math.floor(n / 100)] + ' hundred' + (n % 100 ? ' ' + cardinalEn(n % 100) : '');
  if (n < 1e6) return cardinalEn(Math.floor(n / 1000)) + ' thousand' + (n % 1000 ? ' ' + cardinalEn(n % 1000) : '');
  if (n < 1e12) return cardinalEn(Math.floor(n / 1e6)) + ' million' + (n % 1e6 ? ' ' + cardinalEn(n % 1e6) : '');
  return String(n);
}

export function yearEn(n) {
  const hi = Math.floor(n / 100);
  const lo = n % 100;
  if (n % 1000 === 0 || (n >= 2000 && n <= 2009)) return cardinalEn(n);
  if (lo === 0) return cardinalEn(hi) + ' hundred';
  if (lo < 10) return cardinalEn(hi) + ' oh ' + cardinalEn(lo);
  return cardinalEn(hi) + ' ' + cardinalEn(lo);
}

function splitDecimal(raw, es) {
  const thousands = es ? '.' : ',';
  const groups = raw.split(/[.,]/);
  if (groups.length === 1) return [raw, ''];
  const seps = raw.replace(/[^.,]/g, '');
  if (groups.slice(1).every((g) => g.length === 3) && [...seps].every((c) => c === thousands)) return [groups.join(''), ''];
  return [groups.slice(0, -1).join(''), groups.at(-1)];
}

const digit = (c, es) => (es ? UNITS_ES : UNITS_EN)[Number(c)] ?? '';

function number(raw, es) {
  const [int, frac] = splitDecimal(raw, es);
  if (int.length > 12) return [...int].map((c) => digit(c, es)).join(' ');
  const whole = Number(int);
  const words = es ? cardinalEs(whole) : !frac && raw.length === 4 && whole >= 1100 && whole <= 2099 ? yearEn(whole) : cardinalEn(whole);
  return frac ? words + (es ? ' coma ' : ' point ') + [...frac].map((c) => digit(c, es)).join(' ') : words;
}

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const abbr = (pairs) => pairs.map(([a, w]) => [new RegExp('(?<!\\p{L})' + escape(a) + '(?!\\p{L})', 'gu'), w]);
const ABBR_ES = abbr([
  ['EE. UU.', 'Estados Unidos'], ['EE.UU.', 'Estados Unidos'], ['Srta.', 'señorita'], ['Sra.', 'señora'], ['Sr.', 'señor'],
  ['Dra.', 'doctora'], ['Dr.', 'doctor'], ['Ud.', 'usted'], ['Uds.', 'ustedes'], ['etc.', 'etcétera'], ['aprox.', 'aproximadamente'],
  ['Cap.', 'capítulo'], ['cap.', 'capítulo'], ['pág.', 'página'], ['núm.', 'número'], ['nº', 'número'], ['Nº', 'número'],
  ['vs.', 'contra'], ['p. ej.', 'por ejemplo'], ['Vol.', 'volumen'], ['vol.', 'volumen']
]);
const ABBR_EN = abbr([
  ['Mrs.', 'missus'], ['Mr.', 'mister'], ['Ms.', 'miz'], ['Dr.', 'doctor'], ['St.', 'saint'], ['vs.', 'versus'], ['etc.', 'et cetera'],
  ['e.g.', 'for example'], ['i.e.', 'that is'], ['Ch.', 'chapter'], ['Vol.', 'volume'], ['No.', 'number']
]);

const NUMBER = /(?<![\p{L}\d])\d{1,3}(?:[.,]\d{3})+(?:[.,]\d+)?(?![\p{L}\d])|(?<![\p{L}\d])\d+(?:[.,]\d+)?(?![\p{L}\d])/gu;

export function normalizeSpeech(text, lang = 'es') {
  const es = !String(lang).toLowerCase().startsWith('en');
  let s = String(text || '');
  s = s.replace(/\.{3,}/g, '…').replace(/([!?¡¿])\1+/g, '$1').replace(/[*~_]{2,}/g, ' ');
  s = s.replace(/(^|\n)\s*[—–]\s*/g, '$1').replace(/\s*[—–]\s*/g, ', ').replace(/,\s*([,.;:!?…])/g, '$1');
  s = s.replace(/(?<!\p{L})etc\.(?=\s+\p{Lu}|\s*$)/gu, es ? 'etcétera.' : 'et cetera.');
  for (const [re, w] of es ? ABBR_ES : ABBR_EN) s = s.replace(re, w);
  s = s.replace(/(\d+(?:[.,]\d+)?)\s?%/g, (m, n) => number(n, es) + (es ? ' por ciento' : ' percent'));
  if (es) {
    s = s.replace(/(?<!\d)(\d{1,2})\.?([ºª])/g, (m, n, mark) => {
      const w = ORDINALS_ES[Number(n)];
      return w ? (mark === 'ª' ? w.slice(0, -1) + 'a' : w) : m;
    });
  }
  s = s.replace(NUMBER, (m) => number(m, es));
  return s.replace(/\s+/g, ' ').trim();
}
