<script>
  // Speed of the auto-scroll, always at hand while it runs: slower, the current speed, faster, stop.
  // Taps here never reach the page underneath (a tap on the page stops the scroll).
  import Icon from './Icon.svelte';

  /** value: pixels per second; base: the speed shown as ×1. */
  let { value, base, min, max, onchange, onstop, label = 'Desplazamiento automático' } = $props();

  const STEP = 1.15;
  const clamp = (v) => Math.round(Math.min(max, Math.max(min, v)));
  const factor = $derived((value / base).toFixed(value / base < 1 ? 2 : 1).replace(/\.0+$/, '').replace('.', ','));

  function key(e) {
    if (e.target.closest?.('input, select, textarea')) return;
    if (e.key === '+' || e.key === '=') { e.preventDefault(); onchange(clamp(value * STEP)); }
    if (e.key === '-' || e.key === '_') { e.preventDefault(); onchange(clamp(value / STEP)); }
  }
</script>

<svelte:window onkeydown={key} />

<div class="speed" role="toolbar" aria-label={label} onclick={(e) => e.stopPropagation()} onpointerup={(e) => e.stopPropagation()}>
  <span class="small lbl">{label}</span>
  <button class="icon-btn" onclick={() => onchange(clamp(value / STEP))} disabled={value <= min} aria-label="Más lento" title="Más lento (−)"><Icon name="remove" size={20} /></button>
  <span class="val" aria-live="polite">×{factor}</span>
  <button class="icon-btn" onclick={() => onchange(clamp(value * STEP))} disabled={value >= max} aria-label="Más rápido" title="Más rápido (+)"><Icon name="add" size={20} /></button>
  <button class="icon-btn" onclick={onstop} aria-label="Parar" title="Parar"><Icon name="pause" size={20} /></button>
</div>

<style>
  .speed { position: fixed; left: 50%; transform: translateX(-50%); bottom: calc(var(--safe-bottom) + 14px); z-index: 25; display: flex; align-items: center; gap: 2px; padding: 4px 6px 4px 14px; border-radius: 999px; background: #111e; color: #fff; backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); box-shadow: 0 6px 20px #0008; max-width: calc(100vw - 24px); }
  .speed .icon-btn { color: #fff; }
  .speed .icon-btn:disabled { opacity: 0.35; }
  .lbl { color: #fffb; margin-right: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .val { min-width: 48px; text-align: center; font-weight: 700; font-variant-numeric: tabular-nums; }
  @media (max-width: 380px) { .lbl { display: none; } }
</style>
