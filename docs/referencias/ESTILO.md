# Estilo de Yomukai — «Tinta y viñeta»

Dirección visual del bloque 7 (pedido del 2026-11-10). La **estructura** sigue siendo la de Netflix/Seanime: hero con
carrusel, filas horizontales, fichas con banner, navegación clara, el contenido primero. Lo que cambia es la **piel**:
neobrutalismo con medida, hecho con el lenguaje del manga, el anime y el cómic. Personalidad sí, ruido no.

> Petición del usuario (2026-11-11): «neobrutalismo con esencia de manga, anime y cómic, con personalidad, usando el
> neobrutalismo bien, y sin perder el flow estilo Netflix en estructura».

## Qué tomamos de cada referencia (capturas en `neobrutalismo/`, escritorio y móvil)

| Referencia | Qué aprendemos | Qué NO copiamos |
|---|---|---|
| **Gumroad Discover** (principal para el catálogo) | Tarjetas de producto con borde fino y sólido, etiqueta de precio con forma (banderín), pestañas de categoría en una fila, filtros en un panel con bordes. Mucho aire. | Fondo claro plano sin jerarquía; tarjetas sin portada protagonista. |
| **RetroUI / neobrutalism.dev** | Botones con borde 2px y sombra dura desplazada que «se hunden» al pulsar; resaltado de palabras con bloque de color; ventanas con borde y sombra de un color. | Amarillo chillón por todas partes; tipografía mono en textos. |
| **Byooooob** | Formas juguetonas (estallidos, estrellas), cartas inclinadas con borde, tipografía display condensada enorme, cinta de texto en movimiento. | El exceso de elementos a la vez y los colores sin jerarquía. |
| **Cyan Banister** | Personalidad con pixel/ilustración junto a tipografía moderna y legible. | El tamaño de los adornos (aquí mandan las portadas). |
| **Sui Overflow** | Jerarquía solo con el tamaño de letra: números y títulos grandes, texto pequeño y limpio. | — |
| **99% Off Sale (CAH)** | Rejilla densa de productos que se lee bien gracias a bordes y separación constantes. | Los parpadeos y la animación agresiva. |
| **Tony's Chocolonely (Awwwards)** | Ficha de producto con bloques de color planos, titular display, información en paneles con borde. | La paleta caramelo. |
| **dodonut.com** | Ahora redirige a otra agencia (bejamas) y ya no es neobrutalista: descartada. | — |
| **Seanime** (`docs/referencias/1-10.png`, `ANALISIS.md`) | La estructura: hero, filas, ficha con banner, reproductor limpio. Solo ideas (GPLv3: ni código ni recursos). | — |

## La fórmula

1. **Estructura Netflix intacta**: mismo esqueleto de páginas, mismos flujos, mismos atajos. Nadie tiene que aprender
   nada nuevo.
2. **Piel neobrutalista moderada**: bordes visibles de 2 px, sombra dura desplazada (sin difuminar), radio pequeño y
   constante, bloques de color plano. Nunca degradados en la interfaz (las portadas sí pueden tener los suyos).
3. **Alma de manga, anime y cómic** en los detalles, nunca en el texto largo:
   - **Viñetas**: tarjetas y paneles son viñetas, con borde de tinta y esquinas apenas redondeadas.
   - **Trama (halftone)**: puntos muy sutiles (≤ 7 % de opacidad) en el hero, las cabeceras de sección, los
     marcadores de posición sin portada y los estados vacíos.
   - **Bocadillos**: tooltips, avisos y mensajes de chat/DM con forma de bocadillo (borde + colita).
   - **Onomatopeyas**: «¡ZAS!», «¡DOKI!», «¡BAM!» en momentos puntuales: logro desbloqueado, pantalla de carga,
     estados vacíos, «Siguiente» al acabar un episodio. Nunca más de una a la vez en pantalla.
   - **Líneas cinéticas**: en la pantalla de carga y en el «golpe» de un logro.
   - **Tipo de obra con forma propia** (ver abajo).
4. **Reproductor y lectores limpios**: oscuro, controles sobrios que se ocultan, como mucho un detalle de marca (el
   sello 読 en la esquina, el color de acento en la barra). Ahí manda el contenido.

## Paleta: blanco y negro como un manga, con un solo color (decisión del usuario, 2026-11-11)

> «Que la paleta sea tipo negro y blanco, tipo un manga, y después tenga algún toque de color, sin usar más de tres
> colores con este estilo neobrutalista.»

Tres colores y nada más: **negro**, **blanco** y **brasa**. Los grises son tonos del negro (papel a la sombra), no
colores nuevos. Las portadas siguen siendo lo más colorido de la pantalla.

| Token | Color | Uso |
|---|---|---|
| `canvas` | `#0a0a0a` | Fondo: tinta |
| `surface` / `surfaceRaised` / `surfaceHigh` / `surfaceHighest` | `#121212` / `#181818` / `#202020` / `#2a2a2a` | Paneles y tarjetas |
| `ink` | `#f5f5f2` | **Línea de tinta** en negativo: bordes de viñeta y sombras duras |
| `text` / `textDim` / `textFaint` | `#f5f5f2` / `#b4b4b0` / `#85857f` | Texto |
| `accent` («brasa») | `#ff5a1f` | El único color: acción principal, selección, foco, progreso, aviso. Texto encima: negro |
| `accent2`, `accent3`, `type*` | = blanco / brasa | Se conservan como tokens (temas de terceros), pero el tema por defecto no añade colores |

Reglas: el color **solo** marca lo importante (botón principal, pestaña o elemento activo, foco, barra de progreso,
sombra al pasar el ratón). Lo seleccionado en filtros y pestañas va **impreso en blanco** (fondo blanco, texto negro)
con sombra brasa. La ficha ya no toma el color de la portada (`coverAccent: false`).

### Variantes
- **«Papel»** (claro): fondo `#fbfbf8`, tinta `#0a0a0a`, acento `#c2410c`. La página de un manga.
- **«Suave»**: tinta gris (`#6d675e`), colores menos saturados y esquinas más redondas, para quien prefiera menos golpe.
Los temas de antes (OLED, Sepia, Neón…) siguen funcionando sobre los mismos tokens.

## Identidad por tipo de obra: por forma, no por color

| Tipo | Detalle en la tarjeta (en tinta) | Marca junto al nombre |
|---|---|---|
| Anime | Botón ▶ redondo en la esquina | Círculo |
| Manga | Esquina inferior doblada (pliegue) | Cuadrado |
| Manhwa y manhua | Tira vertical en el borde derecho | Cuadrado |
| Cómic | Sombra dura en reposo | Estallido |
| Novela | Lomo de libro a la izquierda | Cuadrado |

## Tipografía

- **Display**: *Dela Gothic One* (Google Fonts, OFL; servida desde el propio sitio). Gruesa, con aire japonés, tiene
  kana. Solo para el logo, títulos de página y sección, números grandes (notas, episodios) y onomatopeyas.
- **Texto**: *Inter* (la actual), muy legible, para todo lo demás: sinopsis, comentarios, interfaz. Las novelas
  mantienen sus fuentes de lectura.
- Jerarquía por tamaño (estilo Sui Overflow): hero 44–56 px, sección 22–26 px, texto 15 px.

## Forma, bordes y sombras

- Borde: **2 px** de tinta en tarjetas, paneles, chips e inputs; **3 px** en botones.
- Sombra dura sin difuminar: **4 px 4 px 0** en tinta. Al pasar el ratón por una tarjeta, la sombra pasa a brasa.
- Radio: **4 px** (casi recto) en botones, pestañas y tarjetas; píldora solo en chips de filtro.
- Aire generoso: separaciones de 16/24/32 px constantes; los bordes gruesos necesitan espacio.

### Botones (buenas prácticas neobrutalistas)
Se repite en las guías de referencia: borde sólido grueso, esquinas rectas, sombra desplazada sin difuminar y un
«clic» físico. Aquí:
- **Primario**: brasa con texto negro. **Secundario**: «papel» blanco con texto negro y sombra brasa. **Tonal**: panel
  oscuro con texto blanco. **Texto**: sin borde ni sombra.
- **Al pasar el ratón** se hunde hasta la mitad de su sombra (2 px). **Al pulsar** se hunde del todo (4 px) y la sombra
  desaparece. Transiciones secas de 90 ms, sin rebotes.
- **Foco** de teclado: contorno de 3 px en brasa separado 4 px. **Desactivado**: sin sombra, borde discontinuo, 50 %.
- 46-48 px de alto (objetivo táctil cómodo); `.small` de 36 px para barras densas.

## Interacción y movimiento

- Botones: al pasar el ratón se hunden hasta la mitad de su sombra; al pulsar, del todo (ver «Botones»). Tarjetas: al
  pasar el ratón se levantan 2 px y su sombra pasa a brasa. 90–180 ms, sin rebotes.
- Foco de teclado siempre visible: contorno de 3 px en brasa.
- Entrada escalonada de las filas (40 ms entre tarjetas, solo la primera vez), transición corta entre páginas, carrusel
  del hero con desplazamiento, mensajes de chat/DM que «aparecen» como bocadillos, logro con golpe de onomatopeya.
- Solo `transform` y `opacity`. Nada bloquea al usuario. `prefers-reduced-motion`: sin desplazamientos, solo fundidos.

## Tarjetas y portadas (F7.1–F7.3)

- Fuera el relleno difuminado a los lados y los recortes raros.
- **Tarjeta vertical = póster vertical**, siempre `object-fit: cover` con la misma proporción 2:3; la pequeña es la
  misma imagen y el mismo recorte que la mediana, solo cambia el tamaño.
- **Tarjeta ancha** (Continuar, hero, calendario) = imagen horizontal: miniatura del episodio, banner de la obra o,
  si no hay, una **composición**: póster a un lado y título y datos al otro sobre color plano del tipo con trama.
- **Nunca sin portada**: marcador de la casa (color del tipo, título en display y trama) mientras carga o si no hay.

## Pantalla de carga (F7.4)

Una tira vertical de viñetas que caen y encajan una a una (con líneas cinéticas) mientras se prepara lo
imprescindible (portadas visibles, primeras filas, lista de capítulos); el sello 読 en la última viñeta. Tiempo máximo:
si algo tarda, se enseña lo que haya con marcadores y se completa después. En navegaciones internas, esqueletos con la
misma forma de viñeta.

## Dónde se aplica

Con fuerza: navegación, Inicio, tarjetas, botones, chips, fichas, perfiles, foro, salas, DMs, logros, estados vacíos.
Con mesura: ajustes, administración. Casi nada: reproductor y lectores.

## Contraste comprobado (WCAG)

El test de `design/themes.json` comprueba cada tema: texto sobre fondo y paneles (≥ 4,5:1), texto secundario,
brasa sobre el fondo (≥ 3:1), texto sobre la brasa (≥ 4,5:1) y la **línea de tinta sobre el fondo (≥ 3:1)**.
Blanco sobre negro: 18,6:1. Negro sobre brasa: 6,4:1. Brasa sobre negro: 6,4:1.
