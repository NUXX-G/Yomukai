<script>
  // Draws what lib/markdown.js parsed: only these elements, text always as text. No {@html} here, ever.
  import { parse } from '../lib/markdown.js';

  let { text = '' } = $props();
  const blocks = $derived(parse(text));
</script>

{#snippet inlines(nodes)}
  {#each nodes as n, i (i)}
    {#if n.t === 'text'}{n.v}
    {:else if n.t === 'b'}<strong>{@render inlines(n.c)}</strong>
    {:else if n.t === 'i'}<em>{@render inlines(n.c)}</em>
    {:else if n.t === 'code'}<code>{n.v}</code>
    {:else if n.t === 'a'}<a href={n.href} target="_blank" rel="noopener noreferrer nofollow ugc">{@render inlines(n.c)}</a>
    {:else if n.t === 'img'}<img src={n.src} alt={n.alt} loading="lazy" />
    {:else if n.t === 'mention'}<a class="mention" href="/u/{encodeURIComponent(n.v)}">@{n.v}</a>
    {/if}
  {/each}
{/snippet}

{#snippet render(list)}
  {#each list as b, i (i)}
    {#if b.t === 'p'}<p>{@render inlines(b.c)}</p>
    {:else if b.t === 'pre'}<pre><code>{b.v}</code></pre>
    {:else if b.t === 'quote'}<blockquote>{@render render(b.c)}</blockquote>
    {:else if b.t === 'ul'}<ul>{#each b.items as item, j (j)}<li>{@render inlines(item)}</li>{/each}</ul>
    {/if}
  {/each}
{/snippet}

<div class="md">{@render render(blocks)}</div>

<style>
  .md { overflow-wrap: anywhere; line-height: 1.55; }
  .md :global(p) { margin: 0 0 0.6em; white-space: pre-wrap; }
  .md :global(p:last-child) { margin-bottom: 0; }
  .md :global(blockquote) { margin: 0 0 0.6em; padding: 6px 12px; border-left: 3px solid var(--c-border-strong); color: var(--c-text-dim); background: var(--c-surface); border-radius: 0 var(--r-md) var(--r-md) 0; }
  .md :global(pre) { margin: 0 0 0.6em; padding: 10px 12px; border-radius: var(--r-md); background: var(--c-surface-high); overflow-x: auto; }
  .md :global(code) { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.88em; }
  .md :global(:not(pre) > code) { padding: 1px 5px; border-radius: 5px; background: var(--c-surface-high); }
  .md :global(ul) { margin: 0 0 0.6em; padding-left: 1.3em; }
  .md :global(a) { color: var(--c-accent); text-decoration: underline; text-underline-offset: 2px; }
  .md :global(a.mention) { text-decoration: none; font-weight: 650; }
  .md :global(img) { display: block; max-width: min(100%, 520px); max-height: 420px; border-radius: var(--r-md); margin: 6px 0; }
</style>
