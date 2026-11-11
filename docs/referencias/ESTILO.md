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

## Paleta (tema por defecto «Tinta y viñeta», oscuro)

| Token | Color | Uso |
|---|---|---|
| `canvas` | `#0e0d0c` | Fondo (tinta cálida, no negro puro) |
| `surface` / `surfaceRaised` / `surfaceHigh` | `#171513` / `#1e1b18` / `#26221e` | Paneles y tarjetas |
| `ink` (nuevo) | `#efe7d6` | **Línea de tinta**: bordes de viñeta y sombras duras (color papel sobre fondo tinta, como un manga en negativo) |
| `text` / `textDim` / `textFaint` | `#f6f1e7` / `#b9b0a1` / `#857c6f` | Texto |
| `accent` («brasa») | `#ff7a3d` | Acción principal, foco, selección. Texto encima: `#1a0a02` |
| `accent2` («viñeta amarilla») | `#ffd23f` | Resaltados tipo rotulador, insignias de «nuevo», onomatopeyas. Siempre con texto oscuro |
| `accent3` («tinta azul») | `#6c8cff` | Enlaces secundarios, información, salas |

Como mucho tres colores con carácter (brasa, amarillo, azul) más los colores por tipo de obra. Cada combinación de
texto y fondo cumple **WCAG AA** (≥ 4,5:1 en texto normal, ≥ 3:1 en texto grande y bordes); se comprueba con el
validador de contraste de `Themes.kt` y el test de `design/themes.json`. El amarillo y los colores claros llevan
siempre texto oscuro.

### Variantes
- **«Papel»** (claro): fondo `#f6efe1`, tinta `#141210`, sombras duras negras. Neobrutalismo clásico, como una página
  de manga.
- **«Suave»**: bordes de 1,5 px, sombras de 2 px, colores menos saturados, para quien prefiera menos golpe.
Los temas actuales (OLED, Sepia, Neón…) siguen funcionando: todo va sobre los mismos tokens.

## Identidad por tipo de obra

Color propio, insignia con forma y un detalle en la tarjeta. El mismo en tarjetas, fichas, filtros, búsqueda y
calendario.

| Tipo | Color | Insignia | Detalle en la tarjeta |
|---|---|---|---|
| Anime | Coral `#ff6b5a` | Etiqueta con triángulo ▶ | Pequeño botón de reproducir en la esquina |
| Manga | Papel `#efe7d6` (texto tinta) | Página con esquina doblada | Esquina inferior doblada (pliegue) |
| Manhwa y manhua | Cian `#3ec9e8` | Tira vertical (scroll) | Borde lateral de tira |
| Cómic | Amarillo `#ffd23f` | Estallido (starburst) | Sombra en el color amarillo |
| Novela | Violeta `#b39bff` | Lomo de libro | Línea de lomo a la izquierda |

## Tipografía

- **Display**: *Dela Gothic One* (Google Fonts, OFL; servida desde el propio sitio). Gruesa, con aire japonés, tiene
  kana. Solo para el logo, títulos de página y sección, números grandes (notas, episodios) y onomatopeyas.
- **Texto**: *Inter* (la actual), muy legible, para todo lo demás: sinopsis, comentarios, interfaz. Las novelas
  mantienen sus fuentes de lectura.
- Jerarquía por tamaño (estilo Sui Overflow): hero 44–56 px, sección 22–26 px, texto 15 px.

## Forma, bordes y sombras

- Borde: **2 px** `ink` en tarjetas, botones, chips, paneles e inputs.
- Sombra dura: **4 px 4 px 0** sin difuminar. En reposo, en `ink` al 85 %; en las tarjetas, al pasar el ratón, la
  sombra toma el **color del tipo** de obra y crece a 6 px.
- Radio: **6 px** en tarjetas y paneles, **8 px** en botones, píldora solo en chips de filtro.
- Aire generoso: separaciones de 16/24/32 px constantes; los bordes gruesos necesitan espacio.

## Interacción y movimiento

- Hover/foco: la tarjeta o el botón **se levanta** (`translate(-2px, -2px)`, la sombra pasa de 4 a 6 px). Al pulsar
  **se hunde** (`translate(2px, 2px)`, sombra a 0). 120–180 ms, `cubic-bezier(0.2, 0, 0, 1)`.
- Foco de teclado siempre visible: contorno de 3 px en `accent2` separado 2 px.
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

## Contraste comprobado (WCAG, 2026-11-11)

| Combinación | Ratio | | Combinación | Ratio |
|---|---|---|---|---|
| texto / canvas | 17,3 | | texto oscuro / amarillo | 12,9 |
| texto / surfaceHigh | 14,0 | | texto oscuro / coral (anime) | 6,7 |
| textDim / canvas | 9,1 | | texto oscuro / cian (manhwa) | 9,5 |
| textFaint / canvas | 4,7 | | texto oscuro / violeta (novela) | 8,1 |
| onAccent / brasa | 7,5 | | texto oscuro / papel (manga) | 15,2 |
| brasa / canvas | 7,5 | | azul / canvas | 6,3 |
| tinta (borde) / canvas | 15,8 | | «Papel»: tinta / fondo | 16,3 |

Todas ≥ 4,5:1. En «Papel», el texto de acento usa `#b54a12` (4,6:1), no la brasa clara.
