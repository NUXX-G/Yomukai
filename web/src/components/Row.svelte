<script>
  import { t } from '../lib/i18n.svelte.js';
  // A horizontal shelf. Fingers swipe it; on a computer, arrows on the sides move it a screen at a time.
  let { children, class: extra = '' } = $props();
  let el = $state();
  let atStart = $state(true);
  let atEnd = $state(false);

  function update() {
    if (!el) return;
    atStart = el.scrollLeft < 8;
    atEnd = el.scrollLeft + el.clientWidth > el.scrollWidth - 8;
  }

  function move(dir) { el.scrollBy({ left: dir * el.clientWidth * 0.85, behavior: 'smooth' }); }

  $effect(() => {
    if (!el) return;
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    const mo = new MutationObserver(update);
    mo.observe(el, { childList: true });
    return () => { ro.disconnect(); mo.disconnect(); };
  });
</script>

<div class="shelf">
  <div class="row {extra}" bind:this={el} onscroll={update}>{@render children?.()}</div>
  {#if !atStart}<button class="arrow left" onclick={() => move(-1)} aria-label={t('Anteriores')}>‹</button>{/if}
  {#if !atEnd}<button class="arrow right" onclick={() => move(1)} aria-label={t('Siguientes')}>›</button>{/if}
</div>

<style>
  .shelf { position: relative; }
  .arrow { display: none; }
  @media (hover: hover) and (pointer: fine) {
    .arrow { display: grid; place-items: center; position: absolute; top: 0; bottom: 48px; width: 44px; z-index: 2; font-size: 2.2rem; line-height: 1; color: var(--text); opacity: 0; transition: opacity 0.2s; }
    .left { left: -8px; background: linear-gradient(90deg, var(--bg) 30%, transparent); }
    .right { right: -8px; background: linear-gradient(270deg, var(--bg) 30%, transparent); }
    .shelf:hover .arrow { opacity: 1; }
  }
</style>
