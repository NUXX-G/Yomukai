<script>
  import { t } from '../lib/i18n.svelte.js';
  import { onMount, onDestroy } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, back, navigate, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { settings, saveSettings } from '../lib/store.svelte.js';
  import { track, markThrough, flush, progressMap } from '../lib/progress.js';
  import { time } from '../lib/format.js';
  import Icon from '../components/Icon.svelte';
  import Sheet from '../components/Sheet.svelte';
  import RoomChat from '../components/RoomChat.svelte';
  import { startPresence } from '../lib/presence.js';
  import { createParty, expectedPosition, correction, copyRoomLink } from '../lib/party.svelte.js';
  import { notify, features } from '../lib/store.svelte.js';
  import { recommendedQuality, qualityOf } from '../lib/nas.js';
  import { createScrubber, fillPercent } from '../lib/scrub.js';
  import { nextEpisode } from '../lib/next-episode.js';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const episodeId = route.params.chapter;
  const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];

  // ---------- Watch party (?sala=CODE) ----------
  let roomCode = $state(route.query.get('sala'));
  let stopPresence;

  // Subtitles transcribed by the home worker (whisper.cpp), cached on the server for everyone.
  let subtitlesUrl = $state(null);
  let subtitlesBusy = $state(false);
  async function subtitles() {
    if (subtitlesUrl) { subtitlesUrl = null; return; }
    subtitlesBusy = true;
    try {
      const res = await api.subtitles(sourceId, episode.url, item?.language === 'EN' ? 'en' : undefined);
      if (res.status === 200) subtitlesUrl = URL.createObjectURL(new Blob([await res.text()], { type: 'text/vtt' }));
      else if (res.status === 202) notify(t('Generando los subtítulos en el servidor de casa; vuelve a pulsar en unos minutos'));
      else notify(t('Los subtítulos necesitan el servidor de casa encendido'));
    } catch (e) { notify(e.message); } finally { subtitlesBusy = false; }
  }
  $effect(() => { const url = subtitlesUrl; return () => { if (url) URL.revokeObjectURL(url); }; });
  let client = $state(null);
  let chatOpen = $state(false);
  let floats = $state([]);
  let floatId = 0;
  /** Set while the page itself moves the video to follow the room, so that move is not sent back as the host's. */
  let applying = false;
  let applyTimer;
  let syncTimer;
  const withRoom = (url) => (roomCode ? url + '?sala=' + encodeURIComponent(roomCode) : url);

  function startParty(code) {
    client?.close();
    client = createParty(code, {
      // by === null: the server itself paused or resumed the room (someone loading); the host obeys that too.
      onState: (state, by) => followState(state, by === null),
      onReact: (emoji) => floatReaction(emoji),
      onNotice: (text) => notify(text)
    });
    clearInterval(syncTimer);
    syncTimer = setInterval(() => { if (client?.party.state && !client.isHost) followState(client.party.state); }, 1000);
  }

  /** Someone else drives: go to their episode, then keep play/pause and position with them. */
  function followState(state, serverDriven = false) {
    if (!state || !episode || !video || (client?.isHost && !serverDriven)) return;
    if (state.sourceId !== sourceId || state.mediaId !== mediaId || state.chapterId !== episode.id) {
      // We just moved here ourselves (Siguiente, the list): the room catches up with us, never the other way round.
      if (client?.canControl && (client.isHost || movedHere())) { shareState(); return; }
      go({ id: state.chapterId }, state.sourceId, state.mediaId);
      return;
    }
    if (loading || !video.duration) return;
    const fix = correction(video.currentTime, expectedPosition(state, client.serverNow()), state.speed || 1);
    applying = true;
    clearTimeout(applyTimer);
    applyTimer = setTimeout(() => (applying = false), 400);
    if (state.playing && video.paused) video.play().catch(() => (ui = true));
    if (!state.playing && !video.paused) video.pause();
    if (fix?.seek != null) video.currentTime = fix.seek;
    video.playbackRate = fix?.rate ?? (state.speed || 1);
  }

  /** The host (or anyone, when allowed) tells the room what plays. */
  function shareState() {
    if (!client?.canControl || applying || !item || !episode || !video) return;
    const server = order(servers)[serverIndex]?.server || '';
    client.sendState({
      sourceId, mediaId, chapterId: episode.id, title: item.title, episode: episode.label, server,
      position: video.currentTime || 0, playing: !video.paused, speed: settings.player.speed
    });
  }

  function floatReaction(emoji) {
    const id = ++floatId;
    floats = [...floats.slice(-30), { id, emoji, x: 8 + Math.random() * 30 }];
    setTimeout(() => (floats = floats.filter((f) => f.id !== id)), 2600);
  }

  /** "Ver con amigos": into your own room, and the link copied for Discord. */
  async function watchTogether() {
    try {
      const room = roomCode ? { code: roomCode } : await api.roomMine();
      roomCode = room.code;
      history.replaceState(history.state, '', withRoom(location.pathname));
      if (!client) startParty(room.code);
      chatOpen = true;
      notify((await copyRoomLink(room.code)) ? t('Enlace de la sala copiado: pásalo a tus amigos') : t('Sala ') + room.code);
      setTimeout(shareState, 500);
    } catch (e) { notify(e.message); }
  }

  let item = $state(null);
  let chapters = $state([]);
  let episode = $state(null);
  let servers = $state([]);
  let serverIndex = $state(-1);
  let error = $state('');
  let loading = $state(true);
  let playing = $state(false);
  let current = $state(0);
  /** Keeps the bar's thumb and filled part together while seeking or dragging (F1.2). */
  const scrubber = createScrubber();
  function onClock() { current = scrubber.clock(video.currentTime, video.seeking); }
  function scrubTo(value) { scrubber.pick(value); current = value; video.currentTime = value; poke(); }
  let duration = $state(0);
  let ui = $state(false);
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
  let wakeLock = null;
  let resumeAt = 0;
  let watched = false;

  /** Episode number -> 'FILLER' | 'MIXED', from AnimeFillerList through the server (empty when unknown). */
  let fillers = $state({});
  /** Opening/ending marks of this episode, worked out by the home worker from the audio; null while unknown. */
  let marks = $state(null);
  /** Seconds left before the next episode starts by itself; null when not counting. */
  let countdown = $state(null);
  let countdownTimer;
  const isFiller = (c) => c?.number != null && fillers[Math.floor(c.number)] === 'FILLER';
  const following = $derived(episode && chapters.find((c) => c.index === episode.index + 1));
  /** The same work's episodes in the user's other sources: "Siguiente" goes on there when this source stops. */
  let others = $state([]);
  const plainPick = $derived(nextEpisode(episode, chapters, others));
  /** With "skip filler" on, the next episode that is not filler (the plain next one otherwise). */
  const nextPick = $derived(settings.player.skipFiller ? nextEpisode(episode, chapters, others, isFiller) : plainPick);
  const next = $derived(nextPick?.episode);
  const skipped = $derived(next && plainPick && next.id !== plainPick.episode.id ? Math.max(1, Math.floor(next.number) - Math.floor(plainPick.episode.number)) : 0);
  function goNext() { if (nextPick) go(nextPick.episode, nextPick.sourceId ?? sourceId, nextPick.mediaId ?? mediaId); }
  async function loadOthers() {
    try {
      const whole = await api.workWhole(sourceId, mediaId);
      others = (whole?.chapters?.chapters || []).filter((c) => c.sourceId !== sourceId && c.mediaId)
        .map((c) => ({ chapterId: c.chapterId, sourceId: c.sourceId, mediaId: c.mediaId, number: c.number, label: t('Ep. {0}', [c.key]), name: c.title }));
    } catch { /* this source's own list stays */ }
  }
  const inOpening = $derived(marks?.openingEnd > 0 && current >= (marks.openingStart ?? 0) && current < marks.openingEnd - 1);
  const inEnding = $derived(marks?.endingStart > 0 && current >= marks.endingStart && current < (marks.endingEnd || duration) - 1);
  // Skipping by itself (Settings), once per opening or ending of each episode.
  let autoSkipped = new Set();
  $effect(() => {
    if (!settings.player.autoSkip) return;
    const key = (episode?.id || '') + (inOpening ? ':op' : inEnding ? ':ed' : '');
    if ((inOpening || inEnding) && !autoSkipped.has(key)) { autoSkipped.add(key); if (inOpening) skipOpening(); else skipEnding(); }
  });
  const direct = $derived(servers.filter((s) => s.direct));

  // Plain MP4 first: the HLS servers some sites use refuse to serve their segments to anyone but them.
  const order = (list) => [...list.filter((s) => s.direct && !s.hls), ...list.filter((s) => s.direct && s.hls)];

  async function play(index) {
    const list = order(servers);
    const server = list[index];
    if (!server) { error = t('Ningún servidor respondió. Prueba con otro desde «Servidores».'); loading = false; return; }
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

  // From the home NAS in a room: the home upload is shared out, so the quality follows how many are watching
  // (unless someone picked a server by hand). Alone, the original.
  let pickedByHand = false;
  const nasAdvice = $derived.by(() => {
    if (!servers.some((s) => qualityOf(s.url))) return null;
    const viewers = client?.party.members.length || 1;
    const q = recommendedQuality(viewers, features.nas?.uploadMbps);
    return { viewers, q, server: order(servers).find((s) => qualityOf(s.url) === q) };
  });
  $effect(() => {
    const advice = nasAdvice;
    if (!advice?.server || pickedByHand || loading) return;
    const now = order(servers)[serverIndex];
    if (!now || qualityOf(now.url) === advice.q) return;
    resumeAt = video.currentTime;
    notify(advice.viewers > 1 ? t('Sala de {0}: calidad «{1}» para que vaya fluido a todos', [advice.viewers, advice.server.quality]) : t('Calidad «{0}»', [advice.server.quality]));
    play(order(servers).indexOf(advice.server));
  });

  function onLoaded() {
    duration = video.duration;
    if (resumeAt > 0 && resumeAt < video.duration - 10) video.currentTime = resumeAt;
    resumeAt = 0;
    loading = false;
  }

  let lastSave = 0;
  function onTime() {
    onClock();
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
    // In a room only whoever drives moves on; the rest follow.
    if (client && !client.canControl) return;
    if (next && settings.player.autoNext) startCountdown();
    else ui = true;
  }

  function startCountdown() {
    clearInterval(countdownTimer);
    countdown = 5;
    countdownTimer = setInterval(() => {
      countdown -= 1;
      if (countdown <= 0) { cancelCountdown(); goNext(); }
    }, 1000);
  }
  function cancelCountdown() { clearInterval(countdownTimer); countdown = null; }

  function skipOpening() { video.currentTime = marks.openingEnd; showFlash('Opening saltado'); api.achievementEvent('skip_opening').catch(() => {}); }
  function skipEnding() {
    if (next) { goNext(); return; }
    video.currentTime = Math.max(0, (marks.endingEnd || duration) - 0.5);
  }

  /** Marks are asked for once; the first time, the worker may still be working them out (they come next time). */
  async function loadMarks() {
    const other = following?.url || chapters.find((c) => c.index === episode.index - 1)?.url;
    try {
      const r = await api.skip(sourceId, episode.url, other, mediaId, episode.number || undefined);
      if (r && typeof r.openingEnd === 'number') marks = r;
      else if (r && typeof r.endingStart === 'number') marks = r;
    } catch { /* no worker, or the feature is off: the +85 s button stays */ }
  }

  async function loadFillers() {
    try {
      const w = await api.work(sourceId, mediaId);
      if (w?.title) fillers = (await api.fillers(w.title)) || {};
    } catch { /* unknown: nothing is marked */ }
  }

  /** Remembers for a moment that this page moved by itself, so the room's old state does not send it back (F1.1). */
  const MOVED = 'player.moved';
  function movedHere() {
    try {
      const m = JSON.parse(sessionStorage.getItem(MOVED) || 'null');
      return !!m && m.chapterId === episode?.id && Date.now() - m.at < 15_000;
    } catch { return false; }
  }
  function go(target, s = sourceId, m = mediaId) {
    cancelCountdown();
    flush();
    if (client?.canControl) {
      try { sessionStorage.setItem(MOVED, JSON.stringify({ chapterId: target.id, at: Date.now() })); } catch {}
      client.sendState({ sourceId: s, mediaId: m, chapterId: target.id, title: item?.title || '', episode: target.label || '', server: '', position: 0, playing: true, speed: settings.player.speed });
    }
    navigate(withRoom(reader('anime', s, m, target.id)), { replace: true });
  }

  function toggle() { video.paused ? video.play() : video.pause(); }
  function seek(delta) { const to = Math.max(0, Math.min(video.duration || 0, scrubber.value + delta)); scrubber.jump(to); current = to; video.currentTime = to; poke(); }

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
      showFlash(delta < 0 ? t('« 10 s') : t('10 s »'));
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
  function toggleMute() { muted = !muted; video.muted = muted; showFlash(muted ? t('Silencio') : t('Volumen {0}%', [Math.round(volume * 100)])); }

  /** Full screen and landscape on Android; on iPhone, Safari's own full-screen video. */
  async function fullscreen() {
    if (document.fullscreenElement || document.webkitFullscreenElement) {
      try { await (document.exitFullscreen || document.webkitExitFullscreen).call(document); } catch {}
      return;
    }
    const request = shell.requestFullscreen || shell.webkitRequestFullscreen;
    try {
      if (!request) throw new Error('no fullscreen');
      await request.call(shell, { navigationUI: 'hide' });
      await screen.orientation?.lock?.('landscape').catch(() => {});
    } catch {
      // iPhone, or an iPad that refuses: Safari's own full-screen video always works.
      try { video.webkitEnterFullscreen?.(); } catch {}
    }
  }

  async function keepAwake(on) {
    try {
      if (on && !wakeLock && 'wakeLock' in navigator) wakeLock = await navigator.wakeLock.request('screen');
      if (!on && wakeLock) { await wakeLock.release(); wakeLock = null; }
    } catch {}
  }

  function setSpeed(s) { settings.player.speed = s; video.playbackRate = s; saveSettings(); shareState(); }

  function key(e) {
    if (e.target.closest?.('input[type=search], input[type=text], textarea, input.vol') || serversOpen || episodesOpen) return;
    // On the focused seek bar the arrows jump like everywhere else (not the bar's own 1 s step, and not twice).
    if (e.target.matches?.('input[type=range]') && e.key.startsWith('Arrow')) e.preventDefault();
    if (e.key === ' ' || e.key === 'k') { e.preventDefault(); toggle(); }
    if (e.key === 'ArrowRight') { seek(e.shiftKey ? 85 : 10); showFlash(e.shiftKey ? t('+85 s') : t('10 s »')); }
    if (e.key === 'ArrowLeft') { seek(-10); showFlash(t('« 10 s')); }
    if (e.key === 'ArrowUp') { e.preventDefault(); setVolume(volume + 0.1); showFlash(t('Volumen {0}%', [Math.round(volume * 100)])); }
    if (e.key === 'ArrowDown') { e.preventDefault(); setVolume(volume - 0.1); showFlash(t('Volumen {0}%', [Math.round(volume * 100)])); }
    if (e.key === 'm') toggleMute();
    if (e.key === 'f') fullscreen();
    if (e.key === 'n' && next) goNext();
    if (e.key === 's' && inOpening) skipOpening();
    if (e.key === 'Escape' && countdown != null) { cancelCountdown(); return; }
    if (e.key === 'Escape' && !document.fullscreenElement) back(mediaHref(sourceId, mediaId));
  }

  onMount(async () => {

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
      loadMarks();
      loadFillers();
      loadOthers();
      stopPresence = startPresence('watching', () => ({ title: item?.title, label: episode?.label, sourceId, mediaId }));
      if (roomCode) startParty(roomCode);
      if (!direct.length) {
        // Only the site's own player: shown full screen on the server's embed page (back here when leaving it).
        const own = servers.find((s) => s.embed);
        if (own) return openEmbed(own);
        loading = false; serversOpen = true; error = t('Este episodio solo se puede ver en la web del servidor.'); return;
      }
      play(0);
      // On a computer the episode fills the screen from the start (the click that opened it allows it).
      if (matchMedia('(pointer: fine)').matches && settings.player?.autoFullscreen !== false && !document.fullscreenElement) fullscreen();
    } catch (e) {
      error = e.message;
      loading = false;
    }
  });

  function openEmbed(s) {
    const after = nextPick ? reader('anime', nextPick.sourceId ?? sourceId, nextPick.mediaId ?? mediaId, nextPick.episode.id) : '';
    location.href = s.embed + '&back=' + encodeURIComponent(mediaHref(sourceId, mediaId)) + (after ? '&next=' + encodeURIComponent(after) : '');
  }

  onDestroy(() => {
    stopPresence?.(); client?.close(); clearInterval(syncTimer); clearTimeout(applyTimer);
    clearInterval(countdownTimer); clearTimeout(hideTimer); clearTimeout(tapTimer); clearTimeout(flashTimer); hls?.destroy();
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
    onpointerup={press} ondblclick={fullscreen} onpointermove={(e) => { if (e.pointerType === 'mouse' && playing) poke(); }} onplay={() => (playing = true)}
    onwaiting={() => { loading = true; if (client?.party.state?.playing) client.loading(); }}
    onplaying={() => { loading = false; client?.ready(); shareState(); }}
    onpause={() => { playing = false; poke(); shareState(); }}
    onseeking={onClock}
    onseeked={() => { onClock(); shareState(); }}
    onloadedmetadata={onLoaded} ontimeupdate={onTime} onended={onEnded} onerror={onError}
  >{#if subtitlesUrl}<track kind="subtitles" label={t('Subtítulos')} src={subtitlesUrl} default />{/if}</video>

  {#each floats as f (f.id)}<span class="float" style:left="{f.x}%" aria-hidden="true">{f.emoji}</span>{/each}
  {#if client && client.party.waiting.length}
    <div class="waiting">{t('Esperando a {0}...', [client.party.waiting.join(', ')])}</div>
  {/if}
  {#if chatOpen && client}
    <div class="chat-panel"><RoomChat {client} onclose={() => (chatOpen = false)} /></div>
  {/if}
  {#if inOpening}
    <button class="skip" onclick={skipOpening}><Icon name="skip" size={20} /> {t('Saltar opening')}</button>
  {:else if inEnding}
    <button class="skip" onclick={skipEnding}><Icon name="skip" size={20} /> {next ? t('Siguiente episodio') : t('Saltar ending')}</button>
  {/if}
  {#if countdown != null && next}
    <div class="upnext" role="status" aria-live="polite">
      <div class="small">{t('Siguiente episodio en {0} s', [countdown])}{#if skipped} {t('· saltando {0} de relleno', [skipped])}{/if}</div>
      <div class="upnext-title">{next.label}{#if next.name} · {next.name}{/if}</div>
      <div class="upnext-actions">
        <button class="btn primary" onclick={() => goNext()}><Icon name="play" size={18} /> {t('Ver ahora')}</button>
        <button class="btn text light" onclick={cancelCountdown}>{t('Cancelar')}</button>
      </div>
    </div>
  {/if}
  {#if loading && !error}<div class="spin"><div class="spinner big"></div></div>{/if}
  {#if flash}<div class="flash">{flash}</div>{/if}
  {#if error}
    <div class="message">
      <p>{error}</p>
      <button class="btn tonal" onclick={() => (serversOpen = true)}>{t('Servidores')}</button>
      <button class="btn text" onclick={() => back(mediaHref(sourceId, mediaId))}>{t('Volver')}</button>
    </div>
  {/if}

  {#if ui}
    <div class="hud" onpointerup={(e) => { if (e.target === e.currentTarget) press(e); }} ondblclick={(e) => { if (e.target === e.currentTarget) fullscreen(); }} onpointermove={(e) => { if (e.pointerType === 'mouse' && playing) poke(); }} role="presentation">
      <div class="top">
        <button class="icon-btn" onclick={() => back(mediaHref(sourceId, mediaId))} aria-label={t('Volver')}><Icon name="back" /></button>
        <div class="title">{item?.title ?? ''} · {episode?.label ?? ''}</div>
        {#if features.flags.rooms !== false}
          {#if client}
            <button class="btn text light chat-btn" onclick={() => { chatOpen = !chatOpen; client.party.unread = 0; }}>
              {t('Sala · {0}', [client.party.members.length])}{#if client.party.unread && !chatOpen}<span class="dot-unread">{client.party.unread}</span>{/if}
            </button>
          {:else}
            <button class="btn text light" onclick={watchTogether} title={t('Abre tu sala y copia el enlace')}>{t('Ver con amigos')}</button>
          {/if}
        {/if}
        {#if features.available?.whisper}
          <button class="btn text light" aria-pressed={!!subtitlesUrl} disabled={subtitlesBusy} onclick={subtitles}>{subtitlesUrl ? t('Quitar subtítulos') : t('Subtítulos')}</button>
        {/if}
        <button class="icon-btn" onclick={() => (episodesOpen = true)} aria-label={t('Episodios')} title={t('Episodios')}><Icon name="list" /></button>
        <button class="btn text light" onclick={() => (serversOpen = true)}>{t('Servidores')}</button>
        <button class="btn text light" onclick={() => setSpeed(SPEEDS[(SPEEDS.indexOf(settings.player.speed) + 1) % SPEEDS.length])}>{settings.player.speed}x</button>
        {#if canPip}<button class="icon-btn" onclick={pip} aria-label={t('Imagen en imagen')} title={t('Imagen en imagen')}><Icon name="pip" /></button>{/if}
        <button class="icon-btn" onclick={fullscreen} aria-label={t('Pantalla completa')} title={t('Pantalla completa (F)')}><Icon name="fullscreen" /></button>
      </div>
      <div class="middle">
        <button class="icon-btn lg" onclick={() => seek(-10)} aria-label={t('Retroceder 10 s')}><Icon name="rewind" size={36} /></button>
        <button class="icon-btn xl" onclick={toggle} aria-label={playing ? 'Pausar' : 'Reproducir'}><Icon name={playing ? 'pause' : 'play'} size={52} /></button>
        <button class="icon-btn lg" onclick={() => seek(10)} aria-label={t('Adelantar 10 s')}><Icon name="forward" size={36} /></button>
      </div>
      <div class="bottom">
        <div class="times"><span>{time(current)}</span><span>{time(duration)}</span></div>
        <input type="range" min="0" max={duration || 0} step="1" value={current} oninput={(e) => scrubTo(+e.target.value)} onchange={() => scrubber.release()} onpointerup={() => scrubber.release()} style="--p:{fillPercent(current, duration)}%" aria-label={t('Progreso')} />
        <div class="actions">
          {#if !marks?.openingEnd}<button class="btn text light" onclick={() => { seek(85); if (current < 300) api.achievementEvent('skip_opening').catch(() => {}); }} title={t('Saltar el opening (85 s)')}>{t('+85 s')}</button>{/if}
          <span class="volume">
            <button class="icon-btn" onclick={toggleMute} aria-label={muted ? 'Activar sonido' : 'Silenciar'}><Icon name={muted || volume === 0 ? 'mute' : 'volume'} /></button>
            <input class="vol" type="range" min="0" max="1" step="0.05" value={muted ? 0 : volume} oninput={(e) => setVolume(+e.target.value)} style="--p:{fillPercent(muted ? 0 : volume, 1)}%" aria-label={t('Volumen')} />
          </span>
          <span class="grow"></span>
          {#if Object.keys(fillers).length}
            <button class="btn text light" aria-pressed={!!settings.player.skipFiller} onclick={() => { settings.player.skipFiller = !settings.player.skipFiller; saveSettings(); }}>
              {settings.player.skipFiller ? 'Saltando relleno' : 'Saltar relleno'}
            </button>
          {/if}
          {#if next}<button class="btn primary" onclick={() => goNext()}><Icon name="next" size={18} /> {t('Siguiente')}{#if skipped} {t('(sin relleno)')}{/if}</button>{/if}
        </div>
      </div>
    </div>
  {/if}
</div>

<Sheet bind:open={episodesOpen} title={t('Episodios')}>
  {#each [...chapters].reverse() as c (c.id)}
    <button class="list-item srv" class:current={c.id === episode?.id} onclick={() => { episodesOpen = false; if (c.id !== episode?.id) go(c); }}>
      <span class="grow" class:seen={watchedIds[c.id]}>{c.label}{#if c.name} <span class="small muted">{c.name}</span>{/if}{#if isFiller(c)} <span class="filler">{t('Relleno')}</span>{/if}</span>
      {#if watchedIds[c.id]}<Icon name="check" size={18} />{/if}
    </button>
  {/each}
</Sheet>

<Sheet bind:open={serversOpen} title={t('Servidores')}>
  {#if nasAdvice && nasAdvice.viewers > 1}
    <p class="small muted nas-note">{t('Desde tu NAS: con {0} en la sala se recomienda «{1}» (la subida de casa es de {2} Mbps).', [nasAdvice.viewers, nasAdvice.server?.quality, features.nas?.uploadMbps || 20])}</p>
  {/if}
  {#each servers as s}
    {#if s.direct}
      <button class="list-item srv" class:current={order(servers)[serverIndex] === s} onclick={() => { serversOpen = false; pickedByHand = true; resumeAt = video.currentTime; play(order(servers).indexOf(s)); }}>
        <Icon name="play" /> <span class="grow">{s.server} <span class="small muted">{s.quality}</span></span>
      </button>
    {:else}
      {#if s.embed}
        <button class="list-item srv" onclick={() => openEmbed(s)}><Icon name="play" /> <span class="grow">{s.server} <span class="small muted">{t('{0} · su reproductor', [s.quality])}</span></span></button>
      {:else}
        <a class="list-item srv" href={s.url} target="_blank" rel="noopener noreferrer"><Icon name="open" /> <span class="grow">{s.server} <span class="small muted">{t('{0} · abrir en su web', [s.quality])}</span></span></a>
      {/if}
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
  .srv { width: 100%; text-align: left; gap: 12px; }
  .chat-panel { position: absolute; z-index: 6; right: 0; top: 0; bottom: 0; width: min(360px, 100%); box-shadow: -10px 0 40px #0008; }
  @media (max-width: 600px) { .chat-panel { top: auto; height: 58%; width: 100%; border-radius: 16px 16px 0 0; overflow: hidden; } }
  .chat-btn { position: relative; }
  .dot-unread { margin-left: 6px; min-width: 18px; height: 18px; padding: 0 5px; border-radius: 999px; background: var(--c-accent); color: var(--c-on-accent); font-size: 0.7rem; display: inline-grid; place-items: center; }
  .waiting { position: absolute; top: calc(var(--safe-top) + 64px); left: 50%; transform: translateX(-50%); z-index: 5; padding: 8px 14px; border-radius: 999px; background: #000b; color: #fff; font-size: 0.85rem; pointer-events: none; }
  .nas-note { margin: 0 0 8px; padding: 0 4px; }
  .float { position: absolute; bottom: 18%; z-index: 5; font-size: 2.2rem; pointer-events: none; animation: rise 2.6s ease-out forwards; }
  @keyframes rise { from { transform: translateY(0) scale(0.8); opacity: 0; } 15% { opacity: 1; transform: translateY(-20px) scale(1.1); } to { transform: translateY(-260px) scale(1); opacity: 0; } }
  @media (prefers-reduced-motion: reduce) { .float { animation: none; opacity: 0.9; } }
  .filler { font-size: 0.7rem; font-weight: 700; padding: 1px 7px; border-radius: 999px; background: color-mix(in srgb, var(--tertiary) 22%, transparent); color: var(--tertiary); }
  .skip {
    position: absolute; right: 24px; bottom: calc(var(--safe-bottom) + 120px); z-index: 4; display: flex; align-items: center; gap: 8px;
    height: 48px; padding: 0 20px; border-radius: 999px; color: #fff; font-weight: 700;
    background: rgb(20 20 20 / 0.72); border: 1px solid rgb(255 255 255 / 0.35); -webkit-backdrop-filter: blur(10px); backdrop-filter: blur(10px);
    animation: in 0.2s ease-out;
  }
  .skip:hover, .skip:focus-visible { background: rgb(255 255 255 / 0.92); color: #000; }
  .upnext {
    position: absolute; right: 24px; bottom: calc(var(--safe-bottom) + 120px); z-index: 5; width: min(360px, calc(100% - 48px)); padding: 16px;
    border-radius: 16px; background: rgb(15 15 15 / 0.9); color: #fff; box-shadow: 0 10px 40px #000a; animation: in 0.2s ease-out;
  }
  .upnext-title { font-weight: 700; margin: 4px 0 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .upnext-actions { display: flex; gap: 8px; }
  @media (max-width: 600px) { .skip, .upnext { right: 16px; bottom: calc(var(--safe-bottom) + 96px); } }
  @media (prefers-reduced-motion: reduce) { .skip, .upnext { animation: none; } }
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
