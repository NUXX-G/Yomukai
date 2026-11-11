// Watch party client: one WebSocket per room, with heartbeat and reconnection. The server's clock is the reference;
// the drift rules are the same as shared/PartySync.kt (seek over 2 s, nudge the speed between 0.3 and 2 s).

import { t } from './i18n.svelte.js';

export const SEEK_THRESHOLD = 2;
export const NUDGE_THRESHOLD = 0.3;

/** Seconds where the party should be at server time `now` (ms). */
export function expectedPosition(state, now) {
  if (!state) return 0;
  return state.playing ? state.position + Math.max(0, now - state.at) / 1000 * (state.speed || 1) : state.position;
}

/** { seek } / { rate } / null, as PartySync.correction. */
export function correction(local, expected, speed = 1) {
  const drift = local - expected;
  if (Math.abs(drift) > SEEK_THRESHOLD) return { seek: expected };
  if (Math.abs(drift) > NUDGE_THRESHOLD) return { rate: speed * (drift < 0 ? 1.05 : 0.95) };
  return null;
}

/**
 * A live room. `party.state` and friends are reactive. Callbacks: onState(state, by, advance), onChat(msg),
 * onReact(emoji, from), onNotice(text), onSkip(chapterId). `advance`: the room itself moved on (queue, votes): everyone
 * goes, the host too. onSkip: votes skipped with nothing queued, so whoever drives goes to the next episode.
 */
export function createParty(code, callbacks = {}) {
  const party = $state({
    code, connected: false, room: null, you: null, host: null, everyoneControls: false,
    members: [], state: null, waiting: [], messages: [], reactions: [], unread: 0, closed: '', offset: 0,
    queue: [], skip: { votes: [], needed: 1 }
  });
  let ws = null;
  let retry = 0;
  let pingTimer = null;
  let retryTimer = null;
  let stopped = false;
  let pingSent = 0;

  const serverNow = () => Date.now() + party.offset;

  function open() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${proto}//${location.host}/api/rooms/${encodeURIComponent(code)}/ws`);
    ws.onopen = () => { party.connected = true; retry = 0; ping(); clearInterval(pingTimer); pingTimer = setInterval(ping, 15_000); };
    ws.onmessage = (e) => { try { handle(JSON.parse(e.data)); } catch { /* ignore a bad frame */ } };
    ws.onclose = (e) => {
      party.connected = false;
      clearInterval(pingTimer);
      if (stopped) return;
      // The server refused the room (unknown, rooms off, other site): do not keep knocking.
      if (e.code === 1008) { party.closed = t(e.reason || 'No se pudo entrar en la sala'); return; }
      retry = Math.min(retry + 1, 6);
      retryTimer = setTimeout(open, Math.min(30_000, 500 * 2 ** retry));
    };
  }

  function ping() { pingSent = Date.now(); send({ t: 'ping' }); }

  function clockFrom(serverTime, sentAt) {
    const now = Date.now();
    const rtt = sentAt ? now - sentAt : 0;
    party.offset = serverTime + rtt / 2 - now;
  }

  function handle(m) {
    switch (m.t) {
      case 'welcome':
        clockFrom(m.now, 0);
        party.room = m.room; party.you = m.you; party.state = m.state; party.waiting = m.waiting || [];
        party.messages = m.history || []; party.reactions = m.reactions || [];
        party.queue = m.queue || []; party.skip = m.skip || { votes: [], needed: 1 };
        members(m);
        if (m.state) callbacks.onState?.(m.state, null);
        break;
      case 'pong': clockFrom(m.now, pingSent); break;
      case 'members': members(m); break;
      case 'state':
        party.state = m.state; party.waiting = m.waiting || [];
        callbacks.onState?.(m.state, m.by, !!m.advance);
        break;
      case 'queue': party.queue = m.items || []; party.skip = m.skip || party.skip; break;
      case 'room': party.room = m.room; break;
      case 'skip': callbacks.onSkip?.(m.chapterId); break;
      case 'chat':
        party.messages = [...party.messages.slice(-199), m.msg];
        party.unread++;
        callbacks.onChat?.(m.msg);
        break;
      case 'react': callbacks.onReact?.(m.emoji, m.from); break;
      case 'notice': callbacks.onNotice?.(m.text); break;
    }
  }

  function members(m) { party.members = m.members; party.host = m.host; party.everyoneControls = !!m.everyoneControls; }

  function send(obj) { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)); }

  open();
  return {
    party,
    serverNow,
    get isHost() { return party.you != null && party.you === party.host; },
    get canControl() { return party.everyoneControls || (party.you != null && party.you === party.host); },
    sendState(state) { send({ t: 'state', state }); },
    loading() { send({ t: 'loading' }); },
    ready() { send({ t: 'ready' }); },
    chat(text, spoiler = false, card = null) { send(card ? { t: 'chat', text, spoiler, card } : { t: 'chat', text, spoiler }); },
    queueAdd(item) { send({ t: 'queue_add', item }); },
    queueRemove(id) { send({ t: 'queue_remove', id }); },
    queueMove(id, to) { send({ t: 'queue_move', id, to }); },
    queuePlay(id) { send({ t: 'queue_play', id }); },
    skip() { send({ t: 'skip' }); },
    ended(chapterId) { send({ t: 'ended', chapterId }); },
    get votedSkip() { return party.you != null && party.skip.votes.includes(party.you); },
    react(emoji) { send({ t: 'react', emoji }); },
    giveHost(userId) { send({ t: 'host', userId }); },
    allowAll(all) { send({ t: 'allow', all }); },
    close() { stopped = true; clearInterval(pingTimer); clearTimeout(retryTimer); ws?.close(); }
  };
}

export const roomLink = (code) => `${location.origin}/sala/${code}`;

/** Copies the room's link (for Discord and friends); true when it worked. */
export async function copyRoomLink(code) {
  try { await navigator.clipboard.writeText(roomLink(code)); return true; } catch { return false; }
}
