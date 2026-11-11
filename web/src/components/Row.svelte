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
    .arrow {
      display: grid; place-items: center; position: absolute; top: 30%; width: 40px; height: 40px; z-index: 5; font-size: 1.8rem; line-height: 1;
      color: var(--c-on-accent2); background: var(--c-accent2); border: var(--nb-border) solid var(--nb-line); border-radius: var(--r-sm);
      box-shadow: 3px 3px 0 var(--nb-line); opacity: 0; transition: opacity 0.2s;
    }
    .arrow:active { transform: translate(2px, 2px); box-shadow: none; }
    .left { left: -6px; }
    .right { right: -2px; }
    .shelf:hover .arrow { opacity: 1; }
  }
</style>
