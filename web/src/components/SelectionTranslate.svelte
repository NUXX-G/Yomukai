<script>
  // Select a word or a phrase inside [root] (double click, drag, or the phone's own long press) and a «Traducir»
  // button appears under it; it opens the word sheet with the translation (F6.3). Works in every reader with text.
  import { t } from '../lib/i18n.svelte.js';
  import { isWord } from '../lib/translate.js';

  /** root: the element holding the text; lang: its language; source: the work's title; onpick(pick). */
  let { root, lang = '', source = '', onpick } = $props();
  let at = $state(null); // { x, y, pick }
  let timer;

  function sentenceOf(text, para) {
    const sentences = para.split(/(?<=[.!?…。！？])\s*/);
    return (sentences.find((x) => x.includes(text)) || para).trim().slice(0, 500);
  }

  function update() {
    clearTimeout(timer);
    // Wait until the hand stops (the selection changes on every move of the handles).
    timer = setTimeout(() => {
      const sel = document.getSelection();
      const text = sel?.toString().replace(/\s+/g, ' ').trim() || '';
      if (!sel || sel.isCollapsed || !text || text.length > MAX || !root || !root.contains(sel.anchorNode) || !root.contains(sel.focusNode)) { at = null; return; }
      const rect = sel.getRangeAt(0).getBoundingClientRect();
      if (!rect.width && !rect.height) { at = null; return; }
      const para = (sel.anchorNode.nodeType === Node.TEXT_NODE ? sel.anchorNode.parentElement : sel.anchorNode)?.closest('p, li, blockquote, div')?.textContent || text;
      const word = isWord(text) ? text.toLowerCase() : text;
      const x = Math.min(window.innerWidth - 70, Math.max(70, rect.left + rect.width / 2));
      // Below the selection (the phone's own menu sits above it); above when there is no room below.
      const below = rect.bottom + 56 < window.innerHeight;
      at = { x, y: below ? rect.bottom + 10 : Math.max(8, rect.top - 52), pick: { word, sentence: sentenceOf(text, para), paragraph: para.slice(0, 2000), lang, source, phrase: !isWord(text) } };
    }, 250);
  }

  function go(e) {
    e.preventDefault();
    e.stopPropagation();
    const pick = at?.pick;
    at = null;
    if (pick) onpick(pick);
  }

  const MAX = 600;
  $effect(() => {
    document.addEventListener('selectionchange', update);
    return () => { document.removeEventListener('selectionchange', update); clearTimeout(timer); };
  });
</script>

{#if at}
  <!-- pointerdown, not click: on phones a tap elsewhere clears the selection before a click would arrive. -->
  <button class="translate-chip" style:left="{at.x}px" style:top="{at.y}px" onpointerdown={go} onclick={(e) => e.stopPropagation()}>
    {t('Traducir')}
  </button>
{/if}

<style>
  .translate-chip {
    position: fixed; z-index: 70; transform: translateX(-50%); padding: 8px 16px; border-radius: 999px; font-weight: 700; font-size: 0.9rem;
    background: var(--primary); color: var(--on-primary); box-shadow: 0 6px 18px #0007; animation: pop 0.12s ease-out;
  }
  @keyframes pop { from { opacity: 0; transform: translate(-50%, 4px); } }
  @media (prefers-reduced-motion: reduce) { .translate-chip { animation: none; } }
</style>
