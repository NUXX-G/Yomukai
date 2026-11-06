// "Watching now" / "reading now" for friends: told when a player or reader opens, every minute while it stays open,
// and cleared when it closes. Guests and errors are ignored (it is only a nicety).
import { api } from './api.js';
import { session } from './store.svelte.js';

export function startPresence(kind, describe) {
  if (!session.user || session.user.guest) return () => {};
  const send = () => {
    const d = describe();
    if (d?.title && !document.hidden) api.presence({ kind, ...d }).catch(() => {});
  };
  const first = setTimeout(send, 3_000);
  const timer = setInterval(send, 60_000);
  return () => {
    clearTimeout(first);
    clearInterval(timer);
    api.presence({ kind: '' }).catch(() => {});
  };
}
