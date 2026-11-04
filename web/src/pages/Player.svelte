<script>
  import { onMount, onDestroy } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, back, navigate, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { settings, saveSettings, notify } from '../lib/store.svelte.js';
  import { track, markThrough, flush, progressMap } from '../lib/progress.js';
  import { clock, time } from '../lib/format.js';
  import Icon from '../components/Icon.svelte';
  import Sheet from '../components/Sheet.svelte';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const episodeId = route.params.chapter;
  const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];

  let item = $state(null);
  let chapters = $state([]);
  let episode = $state(null);
  let servers = $state([]);
  let serverIndex = $state(-1);
  let error = $state('');
  let loading = $state(true);
  let playing = $state(false);
  let current = $state(0);
  let duration = $state(0);
  let ui = $state(false);
  let now = $state(clock());
  let serversOpen = $state(false);
  let episodesOpen = $state(false);
  let muted = $state(false);
  let volume = $state(1);
  let flash = $state('');
  let flashTimer;
  let lastTap = { at: 0, x: 0 };
  let tapTimer;
  let watchedIds = $state({});
  const canPip = typeof document !== 'undefined' && document.pictureInPictureEnabled;
  let video;
  let shell;
  let hls = null;
  let hideTimer;
  let clockTimer;
  let wakeLock = null;
  let resumeAt = 0;
  let watched = false;

  const next = $derived(episode && chapters.find((c) => c.index === episode.index + 1));
  const direct = $derived(servers.filter((s) => s.direct));

  // Plain MP4 first: the HLS servers some sites use refuse to serve their segments to anyone but them.
  const order = (list) => [...list.filter((s) => s.direct && !s.hls), ...list.filter((s) => s.direct && s.hls)];

  async function play(index) {
    const list = order(servers);
    const server = list[index];
    if (!server) { error = 'Ningún servidor respondió. Prueba con otro desde «Servidores».'; loading = false; return; }
    serverIndex = index;
    error = '';
    loading = true;
    hls?.destroy();
    hls = null;
    if (server.hls && !video.canPlayType('application/vnd.apple.mpegurl')) {
      const { default: Hls } = await import('hls.js');
      if (Hls.isSupported()) {
        hls = new Hls({ maxBufferLength: 30 });
        hls.loadSource(server.url);
        hls.attachMedia(video);
        hls.on(Hls.Events.ERROR, (_, data) => { if (data.fatal) play(index + 1); });
      }
    } else {
      video.src = server.url;
    }
    video.playbackRate = settings.player.speed;
    video.play().catch(() => { loading = false; ui = true; });
  }

  function onError() { if (serverIndex >= 0) play(serverIndex + 1); }

  function onLoaded() {
    duration = video.duration;
    if (resumeAt > 0 && resumeAt < video.duration - 10) video.currentTime = resumeAt;
    resumeAt = 0;
    loading = false;
  }

  let lastSave = 0;
  function onTime() {
    current = video.currentTime;
    if (!item || !episode || !video.duration) return;
    if (Date.now() - lastSave > 5000) {
      lastSave = Date.now();
      // Episodes keep seconds in page/total, like the app does.
      track(item, episode, { page: Math.floor(video.currentTime), total: Math.floor(video.duration), read: false });
    }
    if (!watched && video.currentTime / video.duration > 0.9) {
      watched = true;
      markThrough(item, chapters, episode, true).catch(() => {});
    }
  }

  function onEnded() {
    if (next && settings.player.autoNext) go(next);
    else ui = true;
  }

  function go(target) { flush(); navigate(reader('anime', sourceId, mediaId, target.id), { replace: true }); }

  function toggle() { video.paused ? video.play() : video.pause(); }
  function seek(delta) { video.currentTime = Math.max(0, Math.min(video.duration || 0, video.currentTime + delta)); poke(); }

  function poke() {
    ui = true;
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { if (!video?.paused) ui = false; }, 3500);
  }

  function tap() { ui ? (ui = false) : poke(); }

  function showFlash(text) {
    flash = text;
    clearTimeout(flashTimer);
    flashTimer = setTimeout(() => (flash = ''), 700);
  }

  /**
   * Touch: one tap shows or hides the controls, two quick taps on a side jump 10 s.
   * Mouse: a click shows the controls, a double click goes full screen.
   */
  function press(e) {
    if (e.pointerType === 'mouse') { poke(); return; }
    const now = Date.now();
    const x = e.clientX / innerWidth;
    if (now - lastTap.at < 300 && Math.abs(x - lastTap.x) < 0.2 && (x < 0.4 || x > 0.6)) {
      clearTimeout(tapTimer);
      lastTap = { at: 0, x: 0 };
      const delta = x < 0.5 ? -10 : 10;
      seek(delta);
      showFlash(delta < 0 ? '« 10 s' : '10 s »');
      return;
    }
    lastTap = { at: now, x };
    clearTimeout(tapTimer);
    tapTimer = setTimeout(tap, 250);
  }

  async function pip() {
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else await video.requestPictureInPicture();
    } catch {}
  }

  function setVolume(v) {
    volume = Math.min(1, Math.max(0, v));
    video.volume = volume;
    muted = volume === 0;
    video.muted = muted;
    try { localStorage.setItem('player.volume', String(volume)); } catch {}
  }
  function toggleMute() { muted = !muted; video.muted = muted; showFlash(muted ? 'Silencio' : `Volumen ${Math.round(volume * 100)}%`); }

  /** Full screen and landscape on Android; on iPhone, Safari's own full-screen video. */
  async function fullscreen() {
    try {
      if (document.fullscreenElement) { await document.exitFullscreen(); return; }
      if (shell.requestFullscreen) {
        await shell.requestFullscreen({ navigationUI: 'hide' });
        await screen.orientation?.lock?.('landscape').catch(() => {});
      } else if (video.webkitEnterFullscreen) {
        video.webkitEnterFullscreen();
      }
    } catch {}
  }

  async function keepAwake(on) {
    try {
      if (on && !wakeLock && 'wakeLock' in navigator) wakeLock = await navigator.wakeLock.request('screen');
      if (!on && wakeLock) { await wakeLock.release(); wakeLock = null; }
    } catch {}
  }

  function setSpeed(s) { settings.player.speed = s; video.playbackRate = s; saveSettings(); }

  function key(e) {
    if (e.target.closest?.('input[type=search], input[type=text], textarea') || serversOpen || episodesOpen) return;
    if (e.key === ' ' || e.key === 'k') { e.preventDefault(); toggle(); }
    if (e.key === 'ArrowRight') { seek(e.shiftKey ? 85 : 10); showFlash(e.shiftKey ? '+85 s' : '10 s »'); }
    if (e.key === 'ArrowLeft') { seek(-10); showFlash('« 10 s'); }
    if (e.key === 'ArrowUp') { e.preventDefault(); setVolume(volume + 0.1); showFlash(`Volumen ${Math.round(volume * 100)}%`); }
    if (e.key === 'ArrowDown') { e.preventDefault(); setVolume(volume - 0.1); showFlash(`Volumen ${Math.round(volume * 100)}%`); }
    if (e.key === 'm') toggleMute();
    if (e.key === 'f') fullscreen();
    if (e.key === 'n' && next) go(next);
    if (e.key === 'Escape' && !document.fullscreenElement) back(mediaHref(sourceId, mediaId));
  }

  onMount(async () => {
    clockTimer = setInterval(() => (now = clock()), 15_000);
    try {
      const [m, list, progress] = await Promise.all([api.media(sourceId, mediaId), api.chapters(sourceId, mediaId), progressMap(sourceId, mediaId)]);
      item = m;
      chapters = list.chapters;
      episode = chapters.find((c) => c.id === episodeId) || chapters[0];
      const p = progress[episode.id];
      resumeAt = p && !p.read ? p.page : 0;
      watchedIds = Object.fromEntries(Object.values(progress).filter((r) => r.read).map((r) => [r.chapterId, true]));
      try { const v = parseFloat(localStorage.getItem('player.volume')); if (v >= 0 && v <= 1) { volume = v; video.volume = v; } } catch {}
      servers = await api.videos(sourceId, episode.url);
      if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = new MediaMetadata({ title: `${m.title} · ${episode.label}`, artwork: m.cover ? [{ src: m.cover, sizes: '512x512' }] : [] });
      }
      if (!direct.length) { loading = false; serversOpen = true; error = 'Este episodio solo se puede ver en la web del servidor.'; return; }
      play(0);
    } catch (e) {
      error = e.message;
      loading = false;
    }
  });

  onDestroy(() => {
    clearInterval(clockTimer); clearTimeout(hideTimer); clearTimeout(tapTimer); clearTimeout(flashTimer); hls?.destroy();
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {}); keepAwake(false); flush();
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    screen.orientation?.unlock?.();
  });

  $effect(() => { keepAwake(playing); });
</script>

<svelte:window onkeydown={key} />
<svelte:document onvisibilitychange={() => { if (document.visibilityState === 'visible' && playing) { wakeLock = null; keepAwake(true); } }} />

<div class="player" bind:this={shell}>
  <video
    bind:this={video} playsinline preload="auto"
    onpointerup={press} ondblclick={fullscreen} onpointermove={(e) => { if (e.pointerType === 'mouse' && playing) poke(); }} onplay={() => (playing = true)} onpause={() => { playing = false; poke(); }}
    onwaiting={() => (loading = true)} onplaying={() => (loading = false)}
    onloadedmetadata={onLoaded} ontimeupdate={onTime} onended={onEnded} onerror={onError}
  ></video>

  {#if loading && !error}<div class="spin"><div class="spinner big"></div></div>{/if}
  {#if flash}<div class="flash">{flash}</div>{/if}
  {#if error}
    <div class="message">
      <p>{error}</p>
      <button class="btn tonal" onclick={() => (serversOpen = true)}>Servidores</button>
      <button class="btn text" onclick={() => back(mediaHref(sourceId, mediaId))}>Volver</button>
    </div>
  {/if}

  {#if ui}
    <div class="hud" onpointerup={(e) => { if (e.target === e.currentTarget) press(e); }} ondblclick={(e) => { if (e.target === e.currentTarget) fullscreen(); }} onpointermove={(e) => { if (e.pointerType === 'mouse' && playing) poke(); }} role="presentation">
      <div class="top">
        <button class="icon-btn" onclick={() => back(mediaHref(sourceId, mediaId))} aria-label="Volver"><Icon name="back" /></button>
        <div class="title">{item?.title ?? ''} · {episode?.label ?? ''}</div>
        <button class="icon-btn" onclick={() => (episodesOpen = true)} aria-label="Episodios" title="Episodios"><Icon name="list" /></button>
        <button class="btn text light" onclick={() => (serversOpen = true)}>Servidores</button>
        <button class="btn text light" onclick={() => setSpeed(SPEEDS[(SPEEDS.indexOf(settings.player.speed) + 1) % SPEEDS.length])}>{settings.player.speed}x</button>
        {#if canPip}<button class="icon-btn" onclick={pip} aria-label="Imagen en imagen" title="Imagen en imagen"><Icon name="pip" /></button>{/if}
        <button class="icon-btn" onclick={fullscreen} aria-label="Pantalla completa" title="Pantalla completa (F)"><Icon name="fullscreen" /></button>
      </div>
      <div class="middle">
        <button class="icon-btn lg" onclick={() => seek(-10)} aria-label="Retroceder 10 s"><Icon name="rewind" size={36} /></button>
        <button class="icon-btn xl" onclick={toggle} aria-label={playing ? 'Pausar' : 'Reproducir'}><Icon name={playing ? 'pause' : 'play'} size={52} /></button>
        <button class="icon-btn lg" onclick={() => seek(10)} aria-label="Adelantar 10 s"><Icon name="forward" size={36} /></button>
      </div>
      <div class="bottom">
        <div class="times"><span>{time(current)}</span><span>{time(duration)}</span></div>
        <input type="range" min="0" max={duration || 0} step="1" value={current} oninput={(e) => { video.currentTime = +e.target.value; poke(); }} style="--p:{duration ? (current / duration) * 100 : 0}%" aria-label="Progreso" />
        <div class="actions">
          <button class="btn text light" onclick={() => seek(85)}>+85 s</button>
          <span class="volume">
            <button class="icon-btn" onclick={toggleMute} aria-label={muted ? 'Activar sonido' : 'Silenciar'}><Icon name={muted || volume === 0 ? 'mute' : 'volume'} /></button>
            <input class="vol" type="range" min="0" max="1" step="0.05" value={muted ? 0 : volume} oninput={(e) => setVolume(+e.target.value)} style="--p:{(muted ? 0 : volume) * 100}%" aria-label="Volumen" />
          </span>
          <span class="grow"></span>
          {#if next}<button class="btn primary" onclick={() => go(next)}><Icon name="next" size={18} /> Siguiente</button>{/if}
        </div>
      </div>
    </div>
  {:else}
    <div class="mini-clock">{now}</div>
  {/if}
</div>

<Sheet bind:open={episodesOpen} title="Episodios">
  {#each [...chapters].reverse() as c (c.id)}
    <button class="list-item srv" class:current={c.id === episode?.id} onclick={() => { episodesOpen = false; if (c.id !== episode?.id) go(c); }}>
      <span class="grow" class:seen={watchedIds[c.id]}>{c.label}{#if c.name} <span class="small muted">{c.name}</span>{/if}</span>
      {#if watchedIds[c.id]}<Icon name="check" size={18} />{/if}
    </button>
  {/each}
</Sheet>

<Sheet bind:open={serversOpen} title="Servidores">
  {#each servers as s, i}
    {#if s.direct}
      <button class="list-item srv" class:current={order(servers)[serverIndex] === s} onclick={() => { serversOpen = false; play(order(servers).indexOf(s)); }}>
        <Icon name="play" /> <span class="grow">{s.server} <span class="small muted">{s.quality}</span></span>
      </button>
    {:else}
      <a class="list-item srv" href={s.url} target="_blank" rel="noopener noreferrer"><Icon name="open" /> <span class="grow">{s.server} <span class="small muted">{s.quality} · abrir en su web</span></span></a>
    {/if}
  {/each}
</Sheet>

<style>
  .player { position: fixed; inset: 0; background: #000; z-index: 50; }
  video { width: 100%; height: 100%; object-fit: contain; background: #000; }
  .spin { position: absolute; inset: 0; display: grid; place-items: center; pointer-events: none; }
  .big { width: 52px; height: 52px; border-width: 4px; }
  .message { position: absolute; inset: 0; display: grid; place-content: center; gap: 12px; text-align: center; padding: 24px; background: #000c; }
  .hud { position: absolute; inset: 0; display: flex; flex-direction: column; justify-content: space-between; background: #0007; animation: in 0.22s ease-out; color: #fff; }
  .top, .bottom { padding: calc(var(--safe-top) + 8px) 12px 8px; animation: slide-down 0.26s ease-out; }
  .top { display: flex; align-items: center; gap: 4px; }
  .bottom { padding: 8px 16px calc(var(--safe-bottom) + 12px); animation: slide-up 0.26s ease-out; }
  .title { flex: 1; min-width: 0; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .light { color: #fff; }
  .middle { display: flex; justify-content: center; align-items: center; gap: 36px; }
  .icon-btn { color: #fff; }
  .lg { width: 60px; height: 60px; } .xl { width: 84px; height: 84px; }
  .times { display: flex; justify-content: space-between; font-size: 0.8rem; }
  input[type='range'] { width: 100%; appearance: none; height: 4px; border-radius: 2px; margin: 10px 0; background: linear-gradient(90deg, var(--primary) var(--p), #fff4 var(--p)); }
  input[type='range']::-webkit-slider-thumb { appearance: none; width: 16px; height: 16px; border-radius: 50%; background: var(--primary); }
  input[type='range']::-moz-range-thumb { width: 16px; height: 16px; border: 0; border-radius: 50%; background: var(--primary); }
  .actions { display: flex; align-items: center; gap: 8px; }
  .grow { flex: 1; }
  .mini-clock { position: absolute; top: calc(var(--safe-top) + 12px); right: 18px; color: #fff9; font-size: 0.8rem; font-weight: 500; text-shadow: 0 0 6px #000; pointer-events: none; }
  .srv { width: 100%; text-align: left; gap: 12px; }
  .srv.current { color: var(--primary); }
  .seen { opacity: 0.55; }
  .flash { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); z-index: 3; padding: 10px 18px; border-radius: 14px; background: #000a; color: #fff; font-weight: 600; pointer-events: none; animation: in 0.15s; }
  .volume { display: none; align-items: center; }
  .actions .btn { white-space: nowrap; }
  /* Phones: the title gets its own line instead of being squeezed between the buttons. */
  @media (max-width: 600px) {
    .top { flex-wrap: wrap; row-gap: 0; }
    .title { order: 10; flex-basis: 100%; padding: 0 8px 4px 48px; font-size: 0.9rem; }
    .title + * { margin-left: auto; }
    .top .btn { padding: 0 8px; }
  }
  .vol { width: 90px; margin: 0; }
  @media (hover: hover) and (pointer: fine) and (min-width: 601px) { .volume { display: inline-flex; } }
  @keyframes in { from { opacity: 0; } }
  @keyframes slide-down { from { transform: translateY(-30%); opacity: 0; } }
  @keyframes slide-up { from { transform: translateY(30%); opacity: 0; } }
</style>
