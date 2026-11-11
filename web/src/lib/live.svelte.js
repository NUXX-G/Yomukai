// One live stream (SSE) per open page for everything the server pushes to this user: notifications for the bell and
// direct messages (new, typing, seen, deleted). Pages subscribe with on(type, fn) and get an unsubscribe back.
import { api } from './api.js';

const listeners = new Map();
let source = null;

/** Unread direct messages (the badge on the messages icon). */
export const dm = $state({ unread: 0 });

const TYPES = ['notification', 'dm', 'dm_typing', 'dm_seen', 'dm_deleted'];

function dispatch(type, data) {
  for (const fn of listeners.get(type) || []) {
    try { fn(data); } catch { /* one listener failing does not stop the rest */ }
  }
}

/** Opens the stream for a signed-in member (guests have no notifications or messages). */
export function startLive(user) {
  stopLive();
  if (!user || user.guest || typeof EventSource === 'undefined') return;
  source = new EventSource('/api/notifications/stream');
  for (const type of TYPES) {
    source.addEventListener(type, (e) => {
      let data;
      try { data = JSON.parse(e.data); } catch { return; }
      dispatch(type, data);
    });
  }
  // After a reconnection anything may have arrived meanwhile: ask again for the counts.
  source.onopen = () => { refreshDmUnread(); dispatch('reconnect', {}); };
}

export function stopLive() {
  source?.close();
  source = null;
}

export function on(type, fn) {
  if (!listeners.has(type)) listeners.set(type, new Set());
  listeners.get(type).add(fn);
  return () => listeners.get(type)?.delete(fn);
}

export async function refreshDmUnread() {
  try { dm.unread = (await api.dmUnread())?.unread || 0; } catch { /* offline or off: keep the last count */ }
}

// A new message from someone else bumps the badge (unless it is being read right now: that page lowers it again).
on('dm', (d) => { if (d?.message && !d.message.mine && !d.muted) dm.unread++; });
