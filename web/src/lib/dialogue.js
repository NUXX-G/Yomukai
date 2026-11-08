// Reading-aloud text rules, as plain functions (no Svelte state): who speaks, what is heard and in which pieces.
// Kept identical to shared/src/main/kotlin/com/nelson/yomukai/data/speech/SpeechPlan.kt (and Dialogue.kt): the server
// prepares audio overnight with the Kotlin version, and it is only reused if both cut the text the same way.
// shared/src/test/resources/speech_parity.json is checked by both sides' tests.

// --- Who is speaking -------------------------------------------------------------------------

// Whole words, accents included (\b does not see "él" as a word in JavaScript).
const FEMALE = /(?<!\p{L})(ella|ellas|la\s+(?:chica|mujer|joven|niña|muchacha|señora|señorita|princesa|reina|madre|hermana|maga|doncella|dama|anciana|emperatriz|santa|bruja|diosa|maestra|profesora|doctora|capitana)|she|her|the\s+(?:girl|woman|lady|princess|queen|mother|sister|witch|goddess|maid))(?!\p{L})/iu;
const MALE = /(?<!\p{L})(él|ellos|el\s+(?:chico|hombre|joven|niño|muchacho|señor|príncipe|rey|padre|hermano|mago|caballero|anciano|emperador|santo|brujo|dios|maestro|profesor|doctor|capitán|duque)|he|him|his|the\s+(?:boy|man|lord|prince|king|father|brother|wizard|god|knight))(?!\p{L})/iu;

/** "f", "m" or "" from the words around a line of dialogue. */
export function genderHint(narration) {
  const f = FEMALE.test(narration);
  const m = MALE.test(narration);
  return f && !m ? 'f' : m && !f ? 'm' : '';
}

/**
 * A paragraph split into narration and dialogue: [{ text, dialogue, gender }]. Spanish dialogue
 * starts the paragraph with a dash and is cut by the narrator's "—dijo ella—"; English and many
 * translations put it between quotes.
 */
export function splitDialogue(paragraph) {
  const text = paragraph.trim();
  const parts = [];
  const add = (t, dialogue) => { t = t.trim(); if (t && /[\p{L}\p{N}]/u.test(t)) parts.push({ text: t, dialogue, gender: '' }); };
  if (/^[—–―-]\s*\S/.test(text)) {
    // —Line —narration—. Line again.
    text.replace(/^[—–―-]\s*/, '').split(/\s*[—–―]\s*/).forEach((piece, i) => add(piece, i % 2 === 0));
  } else if (/[«“"]/.test(text)) {
    const re = /«([^»]+)»|“([^”]+)”|"([^"]+)"/g;
    let last = 0;
    for (const m of text.matchAll(re)) {
      add(text.slice(last, m.index), false);
      add(m[1] ?? m[2] ?? m[3], true);
      last = m.index + m[0].length;
    }
    add(text.slice(last), false);
  } else {
    add(text, false);
  }
  if (!parts.length) return [{ text, dialogue: false, gender: '' }];
  const narration = parts.filter((p) => !p.dialogue).map((p) => p.text).join(' ');
  const g = genderHint(narration);
  parts.forEach((p) => { if (p.dialogue) p.gender = g; });
  return parts;
}

/** Text as it should be heard: no invisible watermark characters, no markdown escapes, no "***". */
export function speakable(text) {
  return (text || '')
    .replace(/[\u200B-\u200F\u2060-\u2064\uFEFF]/g, '')
    .replace(/\\([[\]()*_~`#>-])/g, '$1')
    .replace(/[*_~#=]{3,}/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Long text in pieces of whole sentences (~400 characters): the first one sounds sooner. */
export function chunks(text, max = 400) {
  if (text.length <= max) return [text];
  const sentences = text.match(/[^.!?…]+[.!?…]+["»”')\]]*\s*|[^.!?…]+$/g) || [text];
  const out = [];
  let current = '';
  for (const s of sentences) {
    if (current && (current + s).length > max) { out.push(current.trim()); current = ''; }
    current += s;
    while (current.length > max * 2) { out.push(current.slice(0, max).trim()); current = current.slice(max); }
  }
  if (current.trim()) out.push(current.trim());
  return out;
}


/**
 * A paragraph as pieces to read, each with its role: [{ text, role: 'narrator'|'female'|'male', dialogue }]. A line
 * whose speaker the text does not tell keeps the narrator's voice: better than a wrong one.
 */
export function planRoles(paragraph, characters = true) {
  const text = speakable(paragraph);
  if (!/[\p{L}\p{N}]/u.test(text)) return [];
  const parts = characters ? splitDialogue(text) : [{ text, dialogue: false, gender: '' }];
  return parts.flatMap((p) => chunks(p.text).map((piece) => ({
    text: piece,
    role: !p.dialogue ? 'narrator' : p.gender === 'f' ? 'female' : p.gender === 'm' ? 'male' : 'narrator',
    dialogue: p.dialogue
  })));
}
