<script>
  import { t } from '../lib/i18n.svelte.js';
  import { onMount, onDestroy, tick } from 'svelte';
  import { startPresence } from '../lib/presence.js';
  // Friends see "reading now" (unless the title is private or the profile hidden).
  const stopPresence = startPresence('reading', () => ({ title: item?.title, label: current?.label, sourceId: route.params.source, mediaId: route.params.id }));
  import { api, downloadChapters } from '../lib/api.js';
  import { route, back, navigate, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { settings, saveSettings, notify, features, sourceById } from '../lib/store.svelte.js';
  import { readingOrder, directionFor, stripMs, analyze, wordsIn, boxAt } from '../lib/ocr.js';
  import { translateTexts } from '../lib/translate.js';
  import { track, markThrough, flush, progressMap } from '../lib/progress.js';
  import { clock } from '../lib/format.js';
  import { detectCrop, cropStyles } from '../lib/crop.js';
  import Icon from '../components/Icon.svelte';
  import ShareSheet from '../components/ShareSheet.svelte';
  import { session } from '../lib/store.svelte.js';
  import Sheet from '../components/Sheet.svelte';
  import WordSheet from '../components/WordSheet.svelte';
  import SpeedControl from '../components/SpeedControl.svelte';
  import { screen, canFullscreen, toggleFullscreen, enterFullscreen, enterFullscreenQuietly, leaveImmersive } from '../lib/fullscreen.svelte.js';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const startId = route.params.chapter;
  // "original" shows each page at its own size, like the sites do: stretching a 720 px webtoon to a
  // wide screen is what made pages look blurry on computers.
  const WIDTHS = [['original', t('Tamaño original')], ['narrow', 'Estrecho'], ['normal', 'Normal'], ['wide', 'Ancho'], ['full', t('Toda la pantalla')]];
  const FILTERS = [['none', 'Normal'], ['sepia', 'Sepia'], ['gray', 'Grises'], ['invert', 'Invertido']];

  let indexTab = $state('chapters');
  let bookmarks = $state([]);
  /** src -> styles for pages with margins cut. */
  const crops = $state({});
  /** src -> [width, height] of every page that loaded. */
  const dims = $state({});
  /** Pages at their own size, but all the same width: the chapter's usual page width (never above 1000 px). */
  const column = $derived.by(() => {
    const w = Object.values(dims).map((d) => d[0]).sort((a, b) => a - b);
    return w.length ? Math.min(1000, Math.max(480, w[Math.floor(w.length / 2)])) : 0;
  });
  /** The usual shape of a chapter's pages: pages still on their way hold that space, so nothing jumps. */
  function usualRatio(pages) {
    const r = pages.map((p) => dims[p]).filter(Boolean).map((d) => d[0] / d[1]).sort((a, b) => a - b);
    return r.length ? r[Math.floor(r.length / 2)] : null;
  }
  let scrolling = $state(false);
  let scrollFrame = 0;

  let item = $state(null);
  let chapters = $state([]);
  /** Chapters on screen, in order: { chapter, pages: [], error } */
  let loaded = $state([]);
  let current = $state(null);
  // Compartir (F4.3): this chapter, with the spoiler shield on by default.
  let shareOpen = $state(false);
  const shareCard = $derived(item && current ? {
    kind: 'chapter', sourceId, mediaId, chapterId: current.id, title: item.title, cover: item.cover || '', type: item.type || '',
    lang: sourceById(sourceId)?.language || '', number: Number.isFinite(current.number) && current.number >= 0 ? current.number : null, label: current.label || ''
  } : null);
  let page = $state(0);
  let ui = $state(true);
  let now = $state(clock());
  let error = $state('');
  let pagedIndex = $state(0);
  let indexOpen = $state(false);
  let optionsOpen = $state(false);
  let indexQuery = $state('');
  /** Pages that finished loading: only they count as seen. */
  const ready = $state({});
  let observer;
  let clockTimer;
  let loadingNext = false;
  /** Page to come back to in the first chapter, until it has been shown. */
  let resumePage = 0;

  const mode = $derived(settings.reader.mode);
  const filter = $derived(settings.reader.filter || 'none');
  const brightness = $derived(settings.reader.brightness ?? 1);
  const marked = $derived(current && bookmarks.some((b) => b.chapterId === current.id && b.position === page));

  async function toggleBookmark() {
    if (!current || !item) return;
    const existing = bookmarks.find((b) => b.chapterId === current.id && b.position === page);
    const row = existing
      ? { ...existing, deleted: true }
      : { sourceId, mediaId, chapterId: current.id, chapterTitle: current.label, position: page, label: '', createdAt: Date.now(), deleted: false };
    bookmarks = existing ? bookmarks.filter((b) => b !== existing) : [row, ...bookmarks];
    notify(existing ? t('Marcador quitado') : t('Marcador en la página {0}', [page + 1]));
    await api.saveBookmark(row).catch((e) => notify(e.message));
  }

  function openBookmark(b) {
    indexOpen = false;
    if (b.chapterId === current?.id) { jumpTo(b.position); return; }
    flush();
    navigate(reader('manga', sourceId, mediaId, b.chapterId) + '?p=' + b.position, { replace: true });
  }

  // ---------- Text on the pages (shared OCR): translation over the bubbles, bubbles read aloud, smart autoscroll ----------
  /** "chapterUrl#page" -> { boxes, tr } ; undefined = not asked yet, null = nobody has read it yet. */
  let ocr = $state({});
  let translating = $state(false);
  let readingBubbles = $state(false);
  /** Languages the bubbles can be translated into (the home worker's translator knows them). */
  const TARGETS = [['es', 'Español'], ['en', 'English'], ['fr', 'Français'], ['de', 'Deutsch'], ['it', 'Italiano'], ['pt', 'Português'], ['ja', '日本語'], ['ko', '한국어'], ['zh', '中文']];
  let speakingBox = $state('');
  const ocrRequested = new Set();
  const ocrOn = $derived(features.flags.ocr !== false);
  const pageKey = (url, i) => url + '#' + i;
  const chapterUrlOf = (id) => loaded.find((l) => l.chapter.id === id)?.chapter.url;
  const textLang = $derived({ ES: 'es', EN: 'en', ZH: 'zh' }[sourceById(sourceId)?.language] ?? 'en');

  async function ensureOcr(chapter, i) {
    const key = pageKey(chapter.url, i);
    if (ocr[key]) return ocr[key];
    try {
      const r = await api.ocr(sourceId, chapter.url, i);
      if (r?.boxes) { ocr[key] = { boxes: r.boxes, tr: null }; return ocr[key]; }
      ocr[key] = null;
      // Nobody has read this page yet: the home worker can, for the whole chapter at once.
      if (!features.available?.ocr && translating && !ocrRequested.has(chapter.url)) {
        ocrRequested.add(chapter.url);
        notify(t('Esta página aún no tiene el texto reconocido; hace falta el servidor de casa (o leerla antes en la app)'));
      }
      if (!ocrRequested.has(chapter.url) && features.available?.ocr) {
        ocrRequested.add(chapter.url);
        api.ocrRequest(sourceId, chapter.url, textLang).catch(() => {});
      }
    } catch { ocr[key] = null; }
    return null;
  }

  // ---------- Long press (or right click) on a bubble: its text and translation (F6.3) ----------
  let wordPick = $state(null);
  let bubblePress = null;
  /** The page and the point (0..1 of the image) under a pointer event, or null. */
  function pagePoint(e) {
    const pg = e.target.closest?.('.pg');
    const img = pg?.querySelector('img');
    if (!img) return null;
    const r = img.getBoundingClientRect();
    const x = (e.clientX - r.left) / r.width;
    const y = (e.clientY - r.top) / r.height;
    if (x < 0 || x > 1 || y < 0 || y > 1) return null;
    let chapter, index;
    if (pg.dataset.chapter) { chapter = loaded.find((l) => l.chapter.id === pg.dataset.chapter)?.chapter; index = Number(pg.dataset.index); }
    else { chapter = flat[pagedIndex]?.chapter; index = flat[pagedIndex]?.i; }
    return chapter ? { chapter, index, x, y } : null;
  }
  async function pickBubble(p) {
    if (!ocrOn) { notify(t('El OCR de cómics está desactivado en este servidor')); return; }
    const data = await ensureOcr(p.chapter, p.index);
    if (!data) {
      notify(features.available?.ocr ? t('Leyendo el texto de este capítulo en el servidor de casa; prueba otra vez en un momento') : t('Para traducir el texto de una imagen hace falta el OCR: el servidor de casa encendido o la app de Android'));
      return;
    }
    const box = boxAt(data.boxes, p.x, p.y);
    if (!box) { notify(t('No hay texto reconocido en ese punto')); return; }
    const text = box.text.replace(/\s+/g, ' ').trim();
    wordPick = { word: text, sentence: text, paragraph: '', lang: textLang, source: item?.title || '', phrase: !/^[\p{L}'’-]{1,40}$/u.test(text) };
  }
  function bubbleDown(e) {
    if (e.button > 0) return;
    const p = pagePoint(e);
    if (!p) return;
    bubblePress = { x: e.clientX, y: e.clientY, timer: setTimeout(() => { bubblePress = null; suppressTap = true; pickBubble(p); }, 550) };
  }
  function bubbleMove(e) { if (bubblePress && Math.hypot(e.clientX - bubblePress.x, e.clientY - bubblePress.y) > 10) { clearTimeout(bubblePress.timer); bubblePress = null; } }
  function bubbleUp() { if (bubblePress) { clearTimeout(bubblePress.timer); bubblePress = null; } }
  function bubbleMenu(e) {
    const p = pagePoint(e);
    if (!p) return;
    e.preventDefault();
    pickBubble(p);
  }
  // The tap that ends a long press must not also show or hide the bars.
  let suppressTap = false;
  function eatTap() { if (suppressTap) { suppressTap = false; return true; } return false; }

  async function translatePage(chapter, i) {
    const data = await ensureOcr(chapter, i);
    if (!data || data.tr) return;
    const bubbles = data.boxes.filter((b) => b.kind !== 'SFX');
    if (!bubbles.length) { data.tr = []; return; }
    const key = pageKey(chapter.url, i);
    if (translatingNow.has(key)) return;
    translatingNow.add(key);
    try {
      const r = await translateTexts(bubbles.map((b) => b.text), textLang, settings.learning?.target || 'es');
      // Still being translated at home (a slow computer): asked again by the retry below.
      if (r?.pending) return;
      ocr[key] = { ...data, tr: r.translations };
    } catch (e) {
      if (e.status === 503) {
        translating = false;
        notify(t('Para traducir hace falta el traductor del servidor de casa o un navegador que traduzca solo (Chrome o Edge actualizados).'));
      } else if (!translateWarned) {
        // One page failing must not switch translation off for the rest: it is tried again.
        translateWarned = true;
        notify(e.message);
      }
    } finally {
      translatingNow.delete(key);
    }
  }
  const translatingNow = new Set();
  let translateWarned = false;

  // Already in the language asked for: nothing to translate, and the reader is told how to pick another.
  $effect(() => {
    if (translating && textLang === (settings.learning?.target || 'es')) {
      translating = false;
      notify(t('Este capítulo ya está en ese idioma: elige otro en «Traducir al».'));
    }
  });
  // While translating, the pages around the one being read are done (and again as the reader moves on).
  $effect(() => {
    if (!translating || !current) return;
    for (let i = Math.max(0, page - 1); i <= page + 4; i++) translatePage(current, i);
  });
  // While translating, pages still without text or translation are tried again every few seconds, even if the reader
  // stays on the same page (the worker may have finished reading or translating them).
  $effect(() => {
    if (!translating) return;
    let ticks = 0;
    const timer = setInterval(() => {
      ticks++;
      if (ticks % 4 === 0) for (const k of Object.keys(ocr)) if (ocr[k] === null) delete ocr[k];
      if (!current) return;
      for (let i = Math.max(0, page - 1); i <= page + 4; i++) translatePage(current, i);
    }, 5_000);
    return () => clearInterval(timer);
  });

  /** Reads the bubbles aloud in reading order from the current page, highlighting and centring the one that sounds. */
  async function toggleBubbles() {
    if (readingBubbles) { readingBubbles = false; speechSynthesis.cancel(); speakingBox = ''; return; }
    if (!('speechSynthesis' in window) || !current) return;
    readingBubbles = true;
    const chapter = current;
    const total = currentPages;
    for (let i = page; i < total && readingBubbles; i++) {
      const data = await ensureOcr(chapter, i);
      if (!data) continue;
      const ordered = readingOrder(data.boxes.filter((b) => b.kind !== 'SFX' || settings.reader.readSfx), directionFor(item?.type));
      for (const b of ordered) {
        if (!readingBubbles) break;
        speakingBox = pageKey(chapter.url, i) + '@' + data.boxes.indexOf(b);
        const el = document.querySelector(`.pg[data-chapter="${CSS.escape(chapter.id)}"][data-index="${i}"]`);
        if (el) {
          const r = el.getBoundingClientRect();
          window.scrollTo({ top: scrollY + r.top + (b.y + b.h / 2) * r.height - innerHeight / 2, behavior: 'smooth' });
        }
        await new Promise((done) => {
          const u = new SpeechSynthesisUtterance(b.text);
          u.lang = textLang;
          u.rate = settings.novel?.speechRate || 1;
          u.onend = done; u.onerror = done;
          speechSynthesis.speak(u);
        });
      }
    }
    readingBubbles = false;
    speakingBox = '';
  }

  /** Smart autoscroll: the strip at the middle of the screen sets the speed (OCR words, else the picture itself). */
  const bandCache = new Map();
  const canvas = typeof document !== 'undefined' ? document.createElement('canvas') : null;
  function smartSpeed(base) {
    const el = document.elementFromPoint(innerWidth / 2, innerHeight / 2)?.closest('.pg');
    if (!el) return base;
    const r = el.getBoundingClientRect();
    // Strips of a fixed height on screen (very tall webtoon images would make "a tenth of a page" huge).
    const band = Math.max(0, Math.floor((innerHeight / 2 - r.top) / STRIP_PX));
    const from = Math.min(1, (band * STRIP_PX) / r.height);
    const to = Math.min(1, ((band + 1) * STRIP_PX) / r.height);
    const url = chapterUrlOf(el.dataset.chapter);
    const key = url ? pageKey(url, Number(el.dataset.index)) : '';
    const cacheKey = key + '|' + band;
    let strip = bandCache.get(cacheKey);
    if (!strip) {
      const data = key ? ocr[key] : null;
      if (key && data === undefined && current) ensureOcr(loaded.find((l) => l.chapter.url === url)?.chapter || current, Number(el.dataset.index));
      if (data?.boxes) strip = { words: wordsIn(data.boxes, from, to), detail: 0.5 };
      else strip = pictureStrip(el.querySelector('img'), from, to);
      if (strip) bandCache.set(cacheKey, strip);
    }
    if (!strip) return base;
    const pxPerSecond = STRIP_PX / (stripMs(strip, settings.reader.wpm || 220) / 1000);
    // The speed control still works: it scales the plan (80 = as planned).
    // Panels without text follow the reader's pace too (text already uses it through the words per minute).
    const pace = strip.words ? 1 : (settings.reader.wpm || 220) / 220;
    return Math.min(900, Math.max(15, pxPerSecond * pace * (base / 80)));
  }
  const STRIP_PX = 300;
  function pictureStrip(img, from, to) {
    if (!img?.naturalWidth || !canvas) return null;
    try {
      const w = 32;
      const h = 16;
      canvas.width = w; canvas.height = h;
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      const sy = from * img.naturalHeight;
      ctx.drawImage(img, 0, sy, img.naturalWidth, Math.max(1, (to - from) * img.naturalHeight), 0, 0, w, h);
      const px = ctx.getImageData(0, 0, w, h).data;
      const grey = new Array(w * h);
      for (let i = 0; i < w * h; i++) grey[i] = (px[i * 4] * 299 + px[i * 4 + 1] * 587 + px[i * 4 + 2] * 114) / 1000;
      return { words: 0, ...analyze(grey, w) };
    } catch { return null; }
  }

  // Auto-scroll for long strips: a steady glide, stopped by a tap or at the end.
  function toggleScroll() {
    scrolling = !scrolling;
    if (scrolling) { ui = false; glide(); } else cancelAnimationFrame(scrollFrame);
  }
  function glide() {
    let last = performance.now();
    let carry = 0;
    let speed = 0;
    const step = (t) => {
      if (!scrolling) return;
      const base = settings.reader.autoSpeed || 80;
      const dt = Math.min(0.1, (t - last) / 1000);
      const target = settings.reader.smartScroll !== false ? smartSpeed(base) : base;
      // Eases from one strip's speed to the next (no sudden jumps between a gutter and a bubble).
      speed = speed ? speed + (target - speed) * Math.min(1, dt * 2.5) : target;
      carry += dt * speed;
      last = t;
      const px = Math.floor(carry);
      if (px > 0) { window.scrollBy(0, px); carry -= px; }
      if (innerHeight + scrollY >= document.documentElement.scrollHeight - 2) { scrolling = false; return; }
      scrollFrame = requestAnimationFrame(step);
    };
    scrollFrame = requestAnimationFrame(step);
  }

  function cropFor(e, src) {
    if (!settings.reader.crop) return;
    const c = detectCrop(e.currentTarget, mode === 'vertical');
    if (c) crops[src] = cropStyles(e.currentTarget, c);
  }
  const rtl = $derived(settings.reader.direction === 'rtl');
  const width = $derived(settings.reader.width || 'original');
  const flat = $derived(loaded.flatMap((l) => l.pages.map((src, i) => ({ src, i, chapter: l.chapter, total: l.pages.length }))));
  const currentPages = $derived(current ? loaded.find((l) => l.chapter.id === current.id)?.pages.length || 0 : 0);
  const indexList = $derived.by(() => {
    const q = indexQuery.trim().toLowerCase();
    const all = [...chapters].reverse();
    return q ? all.filter((c) => (c.label + ' ' + (c.name || '')).toLowerCase().includes(q) || String(c.number) === q.replace(/[^\d.]/g, '')) : all;
  });

  /**
   * Pages come at the width they are shown at (the server shrinks 3000 px scans for a phone), unless
   * the reader asked for original quality. Width buckets are few, so pages are shared in caches.
   */
  function sized(src) {
    if (settings.reader.quality === 'original' || !src.startsWith('/api/img?')) return src;
    const dpr = Math.min(3, window.devicePixelRatio || 1);
    const css = mode === 'paged' ? innerWidth : Math.min(innerWidth, { narrow: 620, normal: 900, wide: 1240, full: innerWidth }[width] ?? 1000);
    return src + '&w=' + Math.round(css * dpr);
  }

  /** Pages already asked for ahead of the reader. */
  const preloaded = new Set();
  /**
   * Keeps the next pages coming: the one after the current at high priority, then a few more, so a
   * page is there when the reader gets to it even when the pages are big.
   */
  function preloadFrom(chapterId, index) {
    const at = flat.findIndex((p) => p.chapter.id === chapterId && p.i === index);
    if (at < 0) return;
    flat.slice(at, at + PRELOAD_AHEAD + 1).forEach((p, k) => {
      if (preloaded.has(p.src) || ready[p.src]) return;
      preloaded.add(p.src);
      const img = new Image();
      img.decoding = 'async';
      if (k <= 2) img.fetchPriority = 'high';
      img.src = p.src;
    });
  }
  const PRELOAD_AHEAD = 8;

  async function loadChapter(chapter) {
    const entry = { chapter, pages: [], error: '' };
    loaded = [...loaded, entry];
    try {
      const pages = (await api.pages(sourceId, chapter.url)).map(sized);
      loaded = loaded.map((l) => (l.chapter.id === chapter.id ? { ...l, pages } : l));
      // The first pages are fetched at once, so the chapter opens without waiting page by page.
      await tick();
      preloadFrom(chapter.id, chapter.id === startId ? resumePage : 0);
    } catch (e) {
      loaded = loaded.map((l) => (l.chapter.id === chapter.id ? { ...l, error: e.message } : l));
    }
  }

  function nextOf(chapter) { return chapters.find((c) => c.index === chapter.index + 1); }
  function prevOf(chapter) { return chapters.find((c) => c.index === chapter.index - 1); }
  function open(chapter) { flush(); navigate(reader('manga', sourceId, mediaId, chapter.id), { replace: true }); }

  async function finished(chapter) {
    if (!item) return;
    await markThrough(item, chapters, chapter, true).catch(() => {});
    const next = nextOf(chapter);
    if (next && settings.reader.infinite && !loaded.some((l) => l.chapter.id === next.id) && !loadingNext) {
      loadingNext = true;
      await loadChapter(next);
      loadingNext = false;
    }
  }

  function seen(chapter, index, total) {
    if (resumePage && chapter.id === startId && index < resumePage) return;
    if (chapter.id === startId) resumePage = 0;
    if (current?.id !== chapter.id) {
      current = chapter;
      history.replaceState(history.state, '', reader('manga', sourceId, mediaId, chapter.id));
    }
    page = index;
    preloadFrom(chapter.id, index);
    if (item) track(item, chapter, { page: index, total, read: false });
    if (index >= total - 2) finished(chapter);
    // Past the middle, the next chapter's page list is fetched ahead: "Siguiente" then opens at once.
    if (index >= total / 2) { const next = nextOf(chapter); if (next) api.pages(sourceId, next.url).catch(() => {}); }
  }

  /** A page that arrives while already on screen counts as seen; the observer would not tell again. */
  function pageLoaded(e, chapter, index, total, src) {
    dims[src] = [e.currentTarget.naturalWidth || 1, e.currentTarget.naturalHeight || 1];
    ready[src] = true;
    if (resumePage && chapter.id === startId) {
      // Back at the page where the user stopped, once the pages above it have their real height.
      const pages = loaded.find((l) => l.chapter.id === startId)?.pages || [];
      if (pages.slice(0, resumePage + 1).every((p) => ready[p])) {
        const target = resumePage;
        resumePage = 0;
        requestAnimationFrame(() => jumpTo(target));
      }
      return;
    }
    const r = e.currentTarget.getBoundingClientRect();
    if (r.top < innerHeight * 0.5 && r.bottom > innerHeight * 0.5) seen(chapter, index, total);
  }

  function observe() {
    observer?.disconnect();
    observer = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting || !e.target.classList.contains('ready')) continue;
        const el = e.target;
        const chapter = chapters.find((c) => c.id === el.dataset.chapter);
        if (chapter) seen(chapter, Number(el.dataset.index), Number(el.dataset.total));
      }
    }, { threshold: 0.5 });
    document.querySelectorAll('[data-page]').forEach((el) => observer.observe(el));
  }

  $effect(() => { flat.length; if (mode === 'vertical') tick().then(observe); });

  /** Vertical mode: jump to a page of the current chapter. */
  function jumpTo(index) {
    if (mode === 'paged') {
      const at = flat.findIndex((p) => p.chapter.id === current?.id && p.i === index);
      if (at >= 0) { pagedIndex = at; showPaged(); }
      return;
    }
    const el = document.querySelector(`[data-chapter="${CSS.escape(current.id)}"][data-index="${index}"]`);
    if (!el) return;
    el.scrollIntoView({ block: 'start' });
    seen(current, index, Number(el.dataset.total));
  }

  // Paged mode: one page at a time, arrows, taps on the sides and swipes.
  function turn(delta) {
    const target = pagedIndex + delta;
    if (target < 0) {
      const prev = current && prevOf(current);
      if (prev) open(prev);
      return;
    }
    if (target >= flat.length) {
      const next = current && nextOf(current);
      if (next && !loaded.some((l) => l.chapter.id === next.id)) loadChapter(next).then(() => { pagedIndex = target; showPaged(); });
      else if (!next) notify(t('Es el último capítulo'));
      return;
    }
    pagedIndex = target;
    showPaged();
  }

  function showPaged() {
    const p = flat[pagedIndex];
    if (!p) return;
    seen(p.chapter, p.i, p.total);
    preloadFrom(p.chapter.id, p.i);
  }

  function key(e) {
    if (e.target.closest?.('input, select, textarea')) return;
    if (e.key === 'Escape' && !indexOpen && !optionsOpen) { back(mediaHref(sourceId, mediaId)); return; }
    if (mode !== 'paged') return;
    const forward = rtl ? 'ArrowLeft' : 'ArrowRight';
    const backward = rtl ? 'ArrowRight' : 'ArrowLeft';
    if (e.key === forward || e.key === 'PageDown' || e.key === ' ') { e.preventDefault(); turn(1); }
    if (e.key === backward || e.key === 'PageUp') { e.preventDefault(); turn(-1); }
  }

  let touchX = 0;
  function tapPaged(e) {
    const x = e.clientX / window.innerWidth;
    if (x < 0.3) turn(rtl ? 1 : -1);
    else if (x > 0.7) turn(rtl ? -1 : 1);
    else ui = !ui;
  }
  function swipe(e) {
    const dx = e.changedTouches[0].clientX - touchX;
    if (Math.abs(dx) > 60) turn((dx < 0) !== rtl ? 1 : -1);
  }

  function setReader(key, value) { settings.reader[key] = value; saveSettings(); }

  onMount(async () => {
    clockTimer = setInterval(() => (now = clock()), 15_000);
    try {
      const [m, list, progress] = await Promise.all([api.media(sourceId, mediaId), api.chapters(sourceId, mediaId), progressMap(sourceId, mediaId)]);
      item = m;
      chapters = list.chapters;
      const start = chapters.find((c) => c.id === startId) || chapters[0];
      if (!start) { error = t('No hay capítulos'); return; }
      const saved = progress[start.id];
      // A bookmark opens at its page; otherwise back where the user stopped.
      const asked = parseInt(route.query.get('p') || '', 10);
      resumePage = asked >= 0 ? asked : saved && !saved.read && saved.page > 0 ? saved.page : 0;
      api.bookmarks(sourceId, mediaId).then((b) => (bookmarks = b)).catch(() => {});
      current = start;
      await loadChapter(start);
      if (mode === 'paged') {
        const at = resumePage ? flat.findIndex((p) => p.i === resumePage) : 0;
        pagedIndex = Math.max(0, at);
        resumePage = 0;
        showPaged();
      }
      setTimeout(() => (ui = false), 2500);
    } catch (e) {
      error = e.message;
    }
  });

  onDestroy(() => { stopPresence(); leaveImmersive(); observer?.disconnect(); clearInterval(clockTimer); cancelAnimationFrame(scrollFrame); scrolling = false; flush(); });
</script>

<!-- With full screen chosen, the first touch in the reader enters it (browsers only allow it after one). -->
<svelte:window onkeydown={key} onpointerup={() => { if (settings.reader.fullscreen) enterFullscreenQuietly(); }} />

<div class="reader f-{filter}" class:paged={mode === 'paged'}>
  {#if brightness < 1}<div class="dim" style:opacity={1 - brightness}></div>{/if}
  {#if error}
    <div class="center"><p>{error}</p><button class="btn tonal" onclick={() => back(mediaHref(sourceId, mediaId))}>{t('Volver')}</button></div>
  {:else if mode === 'vertical'}
    <div class="strip w-{width}" style:max-width={width === 'original' && column ? column + 'px' : null} onclick={() => { if (eatTap()) return; if (scrolling) { scrolling = false; return; } ui = !ui; }} onpointerdown={bubbleDown} onpointermove={bubbleMove} onpointerup={bubbleUp} onpointercancel={bubbleUp} oncontextmenu={bubbleMenu} role="presentation">
      {#each loaded as l, li (l.chapter.id)}
        {@const usual = usualRatio(l.pages)}
        {#if li > 0}<div class="divider">{l.chapter.label}{l.chapter.name ? ' · ' + l.chapter.name : ''}</div>{/if}
        {#if l.error}<p class="center error-text">{l.error}</p>{/if}
        {#if !l.pages.length && !l.error}<div class="center"><div class="spinner"></div></div>{/if}
        {#each l.pages as src, i (src)}
          {@const text = ocr[pageKey(l.chapter.url, i)]}
          <div class="pg" class:ready={ready[src]} class:guess={!ready[src] && usual} class:cropped={crops[src]} style:aspect-ratio={crops[src]?.ratio ?? (!ready[src] && usual ? usual : null)} style:max-width={width === 'original' && crops[src] ? crops[src].natural : null} data-page data-chapter={l.chapter.id} data-index={i} data-total={l.pages.length}>
            <img {src} alt="Página {i + 1}" loading={i < 3 || (li === 0 && i <= resumePage + 2) ? 'eager' : 'lazy'} decoding="async" style:width={crops[src]?.width} style:left={crops[src]?.left} style:top={crops[src]?.top} onload={(e) => { cropFor(e, src); pageLoaded(e, l.chapter, i, l.pages.length, src); }} />
            {#if text && (translating || readingBubbles)}
              <div class="ocr-layer" style:width={crops[src]?.width} style:left={crops[src]?.left} style:top={crops[src]?.top} aria-hidden={!translating}>
                {#each text.boxes as b, j (j)}
                  {@const tIndex = text.boxes.filter((x) => x.kind !== 'SFX').indexOf(b)}
                  {#if translating && b.kind !== 'SFX' && text.tr?.[tIndex]}
                    <div class="tr-bubble" style:left="{b.x * 100}%" style:top="{b.y * 100}%" style:width="{b.w * 100}%" style:min-height="{b.h * 100}%">{text.tr[tIndex]}</div>
                  {:else if speakingBox === pageKey(l.chapter.url, i) + '@' + j}
                    <div class="now-box" style:left="{b.x * 100}%" style:top="{b.y * 100}%" style:width="{b.w * 100}%" style:height="{b.h * 100}%"></div>
                  {/if}
                {/each}
              </div>
            {/if}
          </div>
        {/each}
      {/each}
      {#if current && !nextOf(current) && loaded.at(-1)?.pages.length}<div class="divider">{t('Fin. No hay más capítulos.')}</div>{/if}
      {#if current && nextOf(current) && !settings.reader.infinite}
        <div class="center"><button class="btn primary" onclick={(e) => { e.stopPropagation(); open(nextOf(current)); }}>{t('Siguiente: {0}', [nextOf(current).label])}</button></div>
      {/if}
    </div>
  {:else}
    <div class="single" onclick={(e) => { if (!eatTap()) tapPaged(e); }} onpointerdown={bubbleDown} onpointermove={bubbleMove} onpointerup={bubbleUp} onpointercancel={bubbleUp} oncontextmenu={bubbleMenu} role="presentation" ontouchstart={(e) => (touchX = e.touches[0].clientX)} ontouchend={swipe}>
      {#if flat[pagedIndex]}
        {@const p = flat[pagedIndex]}
        <div class="pg one" class:cropped={crops[p.src]} style:aspect-ratio={crops[p.src]?.ratio} style:width={crops[p.src] ? `min(100vw, calc(100dvh * ${crops[p.src].ratio}))` : null}>
          <img src={p.src} alt="Página {pagedIndex + 1}" style:width={crops[p.src]?.width} style:left={crops[p.src]?.left} style:top={crops[p.src]?.top} onload={(e) => cropFor(e, p.src)} />
        </div>
      {:else}<div class="spinner"></div>{/if}
    </div>
  {/if}

  {#if ui}
    <header class="top">
      <button class="icon-btn" onclick={() => back(mediaHref(sourceId, mediaId))} aria-label={t('Volver')}><Icon name="back" /></button>
      <a class="titles" href={mediaHref(sourceId, mediaId)} title={t('Ir a la ficha')}>
        <div class="t1">{item?.title ?? ''}</div>
        <div class="t2">{current?.label ?? ''}{current?.name ? ' · ' + current.name : ''}</div>
      </a>
      <a class="work-link" href={mediaHref(sourceId, mediaId)} title={t('Ir a la ficha')}><Icon name="info" size={18} /><span>{t('Ficha')}</span></a>
      {#if features.flags.sharing !== false && session.user && !session.user.guest && shareCard}<button class="icon-btn" onclick={() => (shareOpen = true)} aria-label={t('Compartir')} title={t('Compartir')}><Icon name="send" /></button>{/if}
      <button class="icon-btn" class:on={marked} onclick={toggleBookmark} aria-label={marked ? 'Quitar marcador' : t('Poner marcador en esta página')} title={t('Marcador')}><Icon name="bookmark" /></button>
      <button class="icon-btn" onclick={() => (indexOpen = true)} aria-label={t('Índice de capítulos')} title={t('Índice')}><Icon name="list" /></button>
      {#if canFullscreen}<button class="icon-btn" class:on={screen.full} onclick={toggleFullscreen} aria-label={screen.full ? t('Salir de pantalla completa') : 'Pantalla completa'} title={t('Pantalla completa')}><Icon name="fullscreen" /></button>{/if}
      <button class="icon-btn" onclick={() => (optionsOpen = true)} aria-label={t('Ajustes de lectura')} title={t('Ajustes de lectura')}><Icon name="tune" /></button>
    </header>
    <footer class="bottom">
      <div class="track">
        {#if currentPages > 1}
          <input class="slider" type="range" min="0" max={currentPages - 1} value={page} dir={mode === 'paged' && rtl ? 'rtl' : 'ltr'} onchange={(e) => jumpTo(+e.target.value)} aria-label={t('Página')} />
        {/if}
        <span class="small count">{current ? `${page + 1} / ${currentPages || '…'}` : ''}</span>
      </div>
      <div class="actions">
        <button class="btn text nav-btn" disabled={!current || !prevOf(current)} onclick={() => open(prevOf(current))} aria-label={t('Anterior')}><Icon name="chevronLeft" size={20} /><span class="lbl">{t('Anterior')}</span></button>
        {#if ocrOn}
          <!-- Translate, and into which language (changing it translates the pages again): one compact control. -->
          <span class="tr-group">
            <button class="btn text" aria-pressed={translating} onclick={() => (translating = !translating)} title={t('Traducir los bocadillos')}>{translating ? t('Original') : t('Traducir')}</button>
            <select class="tr-lang" value={settings.learning?.target || 'es'} aria-label={t('Traducir al')} title={t('Traducir al')}
              onchange={(e) => { settings.learning = { ...settings.learning, target: e.target.value }; saveSettings(); for (const k of Object.keys(ocr)) if (ocr[k]) ocr[k] = { ...ocr[k], tr: null }; translating = true; }}>
              {#each TARGETS as [code, name] (code)}<option value={code} title={name}>{code.toUpperCase()}</option>{/each}
            </select>
          </span>
          {#if mode === 'vertical'}<button class="icon-btn" class:active={readingBubbles} onclick={toggleBubbles} aria-label={readingBubbles ? t('Parar la lectura') : t('Leer los bocadillos en voz alta')} title={t('Leer los bocadillos')}><Icon name="speech" /></button>{/if}
        {/if}
        {#if mode === 'vertical'}<button class="icon-btn" onclick={toggleScroll} aria-label={scrolling ? 'Parar desplazamiento' : t('Desplazamiento automático')} title={t('Desplazamiento automático')}><Icon name={scrolling ? 'pause' : 'play'} /></button>{/if}
        <button class="btn text nav-btn" disabled={!current || !nextOf(current)} onclick={() => open(nextOf(current))} aria-label={t('Siguiente')}><span class="lbl">{t('Siguiente')}</span><Icon name="chevronRight" size={20} /></button>
      </div>
    </footer>
  {:else}
    <div class="mini-clock">{now}</div>
  {/if}
  {#if scrolling && mode === 'vertical'}
    {#if settings.reader.smartScroll !== false}
      <!-- Smart mode: the control is the reader's reading speed (words per minute); it is remembered and used for all text. -->
      <SpeedControl value={Math.round(80 * (settings.reader.wpm || 220) / 220)} base={80} min={30} max={250}
        onchange={(v) => setReader('wpm', Math.round(Math.min(700, Math.max(80, (220 * v) / 80))))} onstop={toggleScroll} />
    {:else}
      <SpeedControl value={settings.reader.autoSpeed || 80} base={80} min={15} max={800} onchange={(v) => setReader('autoSpeed', v)} onstop={toggleScroll} />
    {/if}
  {/if}
  <!-- While the bubbles are read aloud, stopping is always one tap away (the bars may be hidden). -->
  {#if readingBubbles}
    <button class="stop-pill" onclick={toggleBubbles}><Icon name="pause" size={18} /> {t('Parar la lectura')}</button>
  {/if}
</div>

<Sheet bind:open={indexOpen} title={t('Índice')}>
  <div class="wrap" style="margin-top:0">
    <button class="chip" class:on={indexTab === 'chapters'} onclick={() => (indexTab = 'chapters')}>{t('Capítulos')}</button>
    <button class="chip" class:on={indexTab === 'marks'} onclick={() => (indexTab = 'marks')}>{t('Marcadores · {0}', [bookmarks.length])}</button>
  </div>
  {#if indexTab === 'chapters'}
    <input class="field" type="search" placeholder={t('Buscar capítulo')} bind:value={indexQuery} />
    <div class="index">
      {#each indexList as c (c.id)}
        <button class="list-item idx" class:here={c.id === current?.id} onclick={() => { indexOpen = false; if (c.id !== current?.id) open(c); }}>
          <span class="grow">{c.label}{#if c.name} <span class="muted small">{c.name}</span>{/if}</span>
          {#if c.date}<span class="small muted">{c.date}</span>{/if}
        </button>
      {/each}
    </div>
  {:else if !bookmarks.length}
    <p class="muted">{t('Sin marcadores. Pulsa el marcador de arriba para guardar la página que estás viendo.')}</p>
  {:else}
    {#each bookmarks as b (b.chapterId + '|' + b.position)}
      <button class="list-item idx" onclick={() => openBookmark(b)}>
        <Icon name="bookmark" size={18} /><span class="grow">{t('{0} · página {1}', [b.chapterTitle, b.position + 1])}</span>
      </button>
    {/each}
  {/if}
  {#if current && item}
    <button class="btn outline block" style="margin-top:14px" onclick={() => { indexOpen = false; downloadChapters({ sourceId, mediaId, title: item.title, chapterIds: [current.id] }, (m) => notify(t('No se pudo descargar: ') + m)); }}>
      <Icon name="download" size={18} /> {t('Descargar {0} en este dispositivo', [current.label])}
    </button>
  {/if}
</Sheet>

<Sheet bind:open={optionsOpen} title={t('Lectura')}>
  <div class="small muted">{t('Modo')}</div>
  <div class="wrap">
    <button class="chip" class:on={mode === 'vertical'} onclick={() => setReader('mode', 'vertical')}>{t('Vertical (webtoon)')}</button>
    <button class="chip" class:on={mode === 'paged'} onclick={() => setReader('mode', 'paged')}>{t('Por páginas')}</button>
  </div>
  {#if mode === 'paged'}
    <div class="small muted">{t('Sentido')}</div>
    <div class="wrap">
      <button class="chip" class:on={!rtl} onclick={() => setReader('direction', 'ltr')}>{t('Izquierda a derecha')}</button>
      <button class="chip" class:on={rtl} onclick={() => setReader('direction', 'rtl')}>{t('Derecha a izquierda (manga)')}</button>
    </div>
  {:else}
    <div class="small muted">{t('Ancho de las páginas')}</div>
    <div class="wrap">
      {#each WIDTHS as [k, label]}<button class="chip" class:on={width === k} onclick={() => setReader('width', k)}>{label}</button>{/each}
    </div>
  {/if}
  <label class="toggle"><span class="grow">{t('Pasar solo al siguiente capítulo')}</span><input type="checkbox" checked={settings.reader.infinite} onchange={(e) => setReader('infinite', e.target.checked)} /></label>
  {#if canFullscreen}<label class="toggle"><span class="grow">{t('Pantalla completa al leer (oculta las barras del navegador y del móvil)')}</span><input type="checkbox" checked={!!settings.reader.fullscreen} onchange={(e) => { setReader('fullscreen', e.target.checked); if (e.target.checked) enterFullscreen(); }} /></label>{/if}
  <label class="toggle"><span class="grow">{t('Calidad original (más lenta; si no, las páginas enormes llegan al tamaño de tu pantalla)')}</span><input type="checkbox" checked={settings.reader.quality === 'original'} onchange={(e) => setReader('quality', e.target.checked ? 'original' : 'fast')} /></label>
  <label class="toggle"><span class="grow">{t('Recortar bordes blancos o negros')}</span><input type="checkbox" checked={!!settings.reader.crop} onchange={(e) => setReader('crop', e.target.checked)} /></label>
  <div class="small muted" style="margin-top:12px">{t('Brillo')}</div>
  <input class="range" type="range" min="0.2" max="1" step="0.05" value={brightness} oninput={(e) => setReader('brightness', +e.target.value)} aria-label={t('Brillo')} />
  <div class="small muted">{t('Color')}</div>
  <div class="wrap">
    {#each FILTERS as [k, label]}<button class="chip" class:on={filter === k} onclick={() => setReader('filter', k)}>{label}</button>{/each}
  </div>
  {#if mode === 'vertical'}
    <div class="small muted">{t('Velocidad del desplazamiento automático')}</div>
    <input class="range" type="range" min="15" max="800" step="5" value={settings.reader.autoSpeed || 80} oninput={(e) => setReader('autoSpeed', +e.target.value)} aria-label={t('Velocidad')} />
  {/if}
  <p class="small muted">{t('Teclado: ← → para pasar página, Esc para salir. En el móvil puedes pellizcar para hacer zoom.')}</p>
</Sheet>

<WordSheet bind:pick={wordPick} />

{#if shareCard}<ShareSheet bind:open={shareOpen} card={shareCard} />{/if}

<style>
  .reader { background: #000; min-height: 100vh; color: #fff; }
  .strip { margin: 0 auto; max-width: 900px; }
  .strip.w-narrow { max-width: 620px; }
  .strip.w-wide { max-width: 1240px; }
  .strip.w-full { max-width: none; }
  /* Until a page arrives it holds a tall space: otherwise every unloaded page would be "on screen" at once. */
  .strip .pg { position: relative; min-height: 80vh; background: #0a0a0a; }
  .strip .pg.ready { min-height: 0; background: none; }
  .pg img { display: block; width: 100%; height: auto; }
  .strip.w-original { max-width: 900px; }
  .strip .pg.guess { min-height: 0; }
  .pg.cropped { overflow: hidden; }
  .pg.cropped img { position: absolute; max-width: none; max-height: none; }
  .single .pg.one { position: relative; }
  .single .pg.one.cropped { max-height: 100dvh; }
  .dim { position: fixed; inset: 0; background: #000; pointer-events: none; z-index: 15; }
  .f-sepia .pg img { filter: sepia(0.8); }
  .f-gray .pg img { filter: grayscale(1); }
  .f-invert .pg img { filter: invert(1) hue-rotate(180deg); }
  .icon-btn.on { color: var(--primary); }
  .range { width: 100%; accent-color: var(--primary); margin: 6px 0 14px; }
  .divider { text-align: center; padding: 28px 12px; color: var(--text-dim); font-weight: 600; }
  /* iOS immersive mode: the page uses the tallest view Safari gives once its bars shrink. */
  :global(html.immersive) .single { height: 100lvh; }
  :global(html.immersive) .reader { min-height: 100lvh; }
  .single { height: 100vh; height: 100dvh; display: grid; place-items: center; overflow: hidden; touch-action: pan-y pinch-zoom; }
  .single img { max-width: 100%; max-height: 100vh; max-height: 100dvh; object-fit: contain; user-select: none; -webkit-user-drag: none; }
  .stop-pill { position: fixed; left: 50%; bottom: calc(var(--safe-bottom, 0px) + 24px); transform: translateX(-50%); z-index: 30; display: inline-flex; align-items: center; gap: 8px;
    padding: 12px 20px; border-radius: 999px; border: 0; background: var(--primary); color: var(--on-primary, #fff); font-weight: 650; box-shadow: 0 8px 28px #0009; cursor: pointer; }
  .tr-lang { background: transparent; color: inherit; border: 1px solid #ffffff33; border-radius: 999px; padding: 6px 8px; font: inherit; font-size: 0.85rem; max-width: 110px; }
  .tr-lang option { color: #000; }
  /* Text found on the page: translations drawn over each bubble, and the bubble being read aloud. */
  .ocr-layer { position: absolute; inset: 0; pointer-events: none; }
  .tr-bubble { position: absolute; display: flex; align-items: center; justify-content: center; text-align: center; padding: 2px 4px;
    background: #fff; color: #111; border-radius: 10px; font: 600 clamp(9px, 1.5vw, 15px)/1.15 system-ui, sans-serif; overflow-wrap: anywhere; box-shadow: 0 0 0 2px #fff; }
  .now-box { position: absolute; border: 3px solid var(--c-accent); border-radius: 12px; box-shadow: 0 0 0 4px color-mix(in srgb, var(--c-accent) 30%, transparent); }
  /* Solid bars: pages scrolling behind a see-through bar looked broken. */
  .top, .bottom { position: fixed; left: 0; right: 0; z-index: 20; display: flex; align-items: center; gap: 8px; background: #0c0c0f; animation: in 0.18s ease-out; }
  .top { border-bottom: 1px solid #ffffff14; }
  .bottom { border-top: 1px solid #ffffff14; }
  .top { top: 0; padding: calc(var(--safe-top) + 4px) 8px 6px; }
  .bottom { bottom: 0; flex-direction: column; align-items: stretch; gap: 2px; padding: 6px 8px calc(var(--safe-bottom) + 6px); }
  .track { display: flex; align-items: center; gap: 10px; padding: 0 8px; min-height: 28px; }
  .actions { display: flex; align-items: center; justify-content: space-between; gap: 2px; min-width: 0; }
  .nav-btn { display: inline-flex; align-items: center; gap: 2px; padding-inline: 8px; }
  .tr-group { display: inline-flex; align-items: center; gap: 0; border: 1px solid #ffffff2b; border-radius: 999px; padding-right: 4px; }
  .tr-group .btn { padding-inline: 10px; }
  .tr-group .tr-lang { border: 0; padding: 4px 2px; max-width: none; }
  /* Narrow screens: the arrows alone, so every control fits on one line. */
  @media (max-width: 600px) { .nav-btn .lbl { display: none; } }
  .slider { flex: 1; min-width: 0; accent-color: var(--primary); }
  .count { min-width: 64px; text-align: center; color: var(--text-dim); }
  .titles { flex: 1; min-width: 0; }
  .t1 { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .t2 { font-size: 0.8rem; color: var(--text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mini-clock { position: fixed; top: calc(var(--safe-top) + 8px); right: 12px; z-index: 20; padding: 2px 8px; border-radius: 999px; background: #0006; color: #fffd; font-size: 0.75rem; font-weight: 500; pointer-events: none; }
  .index { margin-top: 8px; }
  .idx { width: 100%; text-align: left; gap: 12px; }
  .idx.here { color: var(--primary); font-weight: 600; }
  .grow { flex: 1; min-width: 0; }
  .wrap { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0 16px; }
  .toggle { display: flex; align-items: center; gap: 12px; padding: 8px 0; }
  @keyframes in { from { opacity: 0; } }
  /* Computers: a slim title bar and a compact floating control panel instead of phone-wide strips. */
  @media (min-width: 1024px) {
    .top { padding: 8px max(16px, calc((100vw - 1400px) / 2)); }
    .bottom { left: 50%; right: auto; bottom: 20px; width: min(760px, calc(100vw - 48px)); transform: translateX(-50%); border: 1px solid #ffffff1f; border-radius: 18px; padding: 8px 10px; box-shadow: 0 12px 40px #000c; }
    .actions .btn { height: 38px; }
    .t1 { font-size: 1.02rem; }
  }

  /* Always-visible way to the work's page (F1.3). */
  .work-link { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; height: 36px; padding: 0 12px; border-radius: 999px; color: inherit; text-decoration: none; font-weight: 600; font-size: 0.9rem; border: 1px solid rgb(255 255 255 / 0.35); }
  .work-link:hover, .work-link:focus-visible { background: rgb(255 255 255 / 0.15); }
  @media (max-width: 480px) { .work-link { padding: 0 9px; } .work-link span { display: none; } }
  a.titles { color: inherit; text-decoration: none; }
  a.titles:hover .t1 { text-decoration: underline; }
</style>
