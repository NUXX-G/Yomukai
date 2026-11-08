<script>
  // /guia — everything Yomukai does, said simply: what it is, how to do it in a few steps, and a button that takes you
  // there. For friends who will never read a README. Only what this server has switched on is shown.
  import { t } from '../lib/i18n.svelte.js';
  import { features, settings, saveSettings, session, notify } from '../lib/store.svelte.js';
  import { navigate } from '../lib/router.svelte.js';
  import Icon from '../components/Icon.svelte';

  const on = (flag) => features.flags[flag] !== false;
  const guest = $derived(!session.user || session.user.guest);

  function startLearning() {
    settings.learning = { ...settings.learning, enabled: true };
    saveSettings();
    notify(t('Modo aprendizaje activado'));
  }

  const sections = $derived([
    {
      icon: 'search', title: t('Encontrar qué leer o ver'),
      what: t('Cada obra sale una sola vez, en tu idioma, aunque esté en varias webs. Yomukai elige la mejor fuente por ti.'),
      steps: [
        t('Busca con la lupa o mira «Recién actualizado» en Inicio.'),
        t('Pulsa una portada: se abre la obra con todos sus capítulos.'),
        t('¿La quieres en otro idioma? Arriba de la ficha tienes los idiomas disponibles.')
      ],
      actions: [[t('Buscar'), '/buscar'], [t('Inicio'), '/']]
    },
    {
      icon: 'book', title: t('Leer manga, manhwa y cómics'),
      what: t('Pasa páginas o baja en tira larga. Toca el centro de la pantalla para ver u ocultar los botones.'),
      steps: [
        t('En tira larga, desplazamiento automático: botón ▶ abajo; va más despacio donde hay texto.'),
        on('ocr') ? t('Traducir: botón «Traducir» abajo y, al lado, el idioma al que quieres traducir.') : null,
        on('ocr') ? t('Leer los bocadillos en voz alta: el altavoz de abajo; para pararlo, el botón «Parar la lectura».') : null,
        t('Marcador, índice y ajustes de lectura: arriba a la derecha.')
      ]
    },
    {
      icon: 'speech', title: t('Novelas con voz'),
      what: t('Cualquier novela se puede escuchar como un audiolibro, con voces distintas para el narrador y los personajes.'),
      steps: [
        t('Abre un capítulo y pulsa el altavoz de arriba.'),
        t('Mientras habla, abajo tienes «Voz» para cambiarla (con botón de probar) y «Parar».'),
        t('La velocidad, en el mismo sitio.')
      ]
    },
    {
      icon: 'tv', title: t('Anime y salas con amigos'),
      what: t('Mira anime en Yomukai y, si quieres, a la vez que tus amigos: el vídeo va sincronizado y hay chat y reacciones.'),
      steps: [
        t('En la ficha de un anime pulsa «Ver con amigos».'),
        t('Se copia el enlace de tu sala: pásalo por WhatsApp o Discord.'),
        t('Quien lo abra entra contigo; si alguien pausa, se pausa para todos.')
      ].filter(() => on('rooms')),
      extra: on('rooms') ? null : t('Las salas están desactivadas en este servidor.'),
      actions: [[t('Ver anime'), '/anime']]
    },
    {
      icon: 'library', title: t('Tu biblioteca y avisos'),
      what: t('Guarda lo que sigues con el corazón de la ficha. Te avisa (campana) cuando sale un capítulo nuevo y se sincroniza con la app.'),
      steps: [t('Corazón en la ficha → «En tu biblioteca».'), t('Tus estados (leyendo, pendiente, terminado) y tu historial, en Biblioteca.')],
      actions: [[t('Biblioteca'), '/biblioteca']]
    },
    on('learning') && !guest ? {
      icon: 'text', title: t('Aprender idiomas'),
      what: t('Lee en el idioma que estudias: toca una palabra para ver qué significa, escucharla y guardarla. Luego repásala con tarjetas.'),
      steps: [
        t('Activa el modo aprendizaje (botón de abajo o en Ajustes).'),
        t('En una novela, mantén pulsada una palabra; en manga, un bocadillo.'),
        t('«Guardar» la añade a tus mazos. En «Repaso» (menú de tu cuenta) practicas un rato al día.'),
        t('Los mazos se pueden compartir y hay ranking entre amigos.')
      ],
      learn: true,
      actions: settings.learning?.enabled ? [[t('Repaso'), '/repaso']] : []
    } : null,
    !guest ? {
      icon: 'star', title: t('Perfil, logros y amigos'),
      what: t('Leer y ver cosas desbloquea logros. Tu perfil enseña lo que llevas; añade amigos para ver su actividad.'),
      steps: [t('Tu perfil: el círculo con tu inicial, arriba a la derecha.'), t('Amigos: en el mismo menú. Busca su nombre y envía la solicitud.')],
      actions: [[t('Mi perfil'), '/u/' + encodeURIComponent(session.user?.name || '')], [t('Amigos'), '/amigos']].concat(on('forum') ? [[t('Foro'), '/foro']] : [])
    } : null,
    {
      icon: 'settings', title: t('Ajustes útiles'),
      what: t('Idioma de la interfaz, temas, qué fuentes usar y la app de Android.'),
      steps: [t('¿Vienes de AniList, MyAnimeList o Mihon? En Ajustes › Importar de otra app traes tu lista.')],
      actions: [[t('Ajustes'), '/ajustes']]
    }
  ].filter(Boolean));
</script>

<div class="page guide">
  <h1>{t('Guía rápida')}</h1>
  <p class="muted lead">{t('Todo lo que puedes hacer en Yomukai, en un minuto.')}</p>
  {#each sections as s (s.title)}
    <section class="card">
      <h2><Icon name={s.icon} size={20} /> {s.title}</h2>
      <p class="what">{s.what}</p>
      {#if s.steps.filter(Boolean).length}
        <ol>{#each s.steps.filter(Boolean) as step (step)}<li>{step}</li>{/each}</ol>
      {/if}
      {#if s.extra}<p class="small muted">{s.extra}</p>{/if}
      <div class="actions">
        {#if s.learn && !settings.learning?.enabled}<button class="btn primary" onclick={startLearning}>{t('Activar el modo aprendizaje')}</button>{/if}
        {#each s.actions || [] as [label, href] (href)}<button class="btn tonal" onclick={() => navigate(href)}>{label}</button>{/each}
      </div>
    </section>
  {/each}
</div>

<style>
  .guide { max-width: 760px; margin: 0 auto; }
  h1 { margin: 16px 0 4px; }
  .lead { margin: 0 0 16px; }
  h2 { display: flex; align-items: center; gap: 8px; }
  h2 :global(svg) { color: var(--primary); flex: none; }
  .what { margin: 0 0 8px; }
  ol { margin: 0 0 8px; padding-left: 20px; display: grid; gap: 4px; color: var(--c-text-dim, inherit); }
  .actions { display: flex; flex-wrap: wrap; gap: 8px; }
  .actions:empty { display: none; }
</style>
