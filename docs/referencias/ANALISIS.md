# Análisis de las referencias (capturas de Seanime)

Solo inspiración: Seanime es GPLv3 y Yomukai MIT. No se copia código, componentes ni recursos; se toman ideas de
estructura, proporción y tratamiento. Yomukai mantiene su identidad (negro «sumi» y acento naranja «brasa»).

## Captura por captura

1. **Inicio (escritorio)**. Rail vertical a la izquierda, solo iconos (~56 px), logo cuadrado arriba, avatar y ajustes
   abajo, globo rojo con número en avisos. Hero a sangre con la imagen del episodio, degradado fuerte hacia negro por
   abajo y por la izquierda; encima, etiqueta pequeña «Continue watching» y título enorme (≈44 px, 800). Arriba a la
   derecha, botones-icono oscuros semitransparentes y puntos de carrusel muy pequeños. Fila «Continuar viendo» con
   miniaturas 16:9 grandes (3 por fila), esquinas ≈8 px, debajo título del episodio en negrita, «Episode 5 / 13 — Serie»
   en gris y la duración («24m») alineada a la derecha. Fila «Continuar leyendo» con pósters 2:3 (7 por fila), insignia
   pequeña de color en la esquina superior derecha.
2. **Reproductor**. Interfaz mínima: arriba a la izquierda nombre de la serie (pequeño, gris) y «Episode 12 Graduation».
   Abajo una barra de progreso fina con marcas (opening/ending) y miniatura flotante con la hora al pasar el ratón.
   Iconos pequeños a los lados. Menú de ajustes flotante: panel oscuro redondeado, filas «etiqueta … valor ›»
   (velocidad, autoplay, siguiente automático, saltar OP/ED, subtítulos).
3. **Biblioteca**. Hero con imagen, debajo cifras grandes centradas (620 GB, 963 archivos...) con etiqueta gris.
   Pestañas de texto con la elegida en «píldora» (Todo, Viendo, Pendiente, Pausado, Completado, Abandonado). Fila de
   desplegables oscuros con icono a la izquierda (orden, formato, estado, temporada). Fila de géneros como texto
   centrado, el elegido en píldora. Rejilla de pósters 2:3 con título y «Fall 2023» debajo en gris; progreso «1/28»
   sobre el póster abajo a la izquierda.
4. **Calendario**. Sección «Release schedule / Based on your anime list»: título grande + subtítulo gris. Calendario
   mensual en una tarjeta con borde sutil; días vacíos solo con número; días con estreno muestran la portada difuminada
   y oscurecida de fondo con «título … Ep. 13». Encima, tarjetas 16:9 de «Missing from your library».
5. **Cola de descargas**. Pestañas segmentadas a lo ancho con contador en globo. Filas en tarjetas oscuras (≈#141414)
   con avatar redondo pequeño, título, estado en cursiva y color, «Added 2 days ago» gris; a la derecha botón pequeño
   oscuro con icono y acción destructiva en rojo como texto.
6. **Ficha de anime**. Banner de fondo oscurecido y con degradado; póster 2:3 a la izquierda (≈200 px); título enorme,
   título alternativo en gris claro, fila de metadatos (progreso 0/20, estado con botón de editar, fecha y temporada),
   nota con corazón en color, estudio y géneros en gris; rankings con iconos; sinopsis en gris de 2-3 líneas; botón
   «Trailer». Fila de acciones con botones-icono y uno con texto. A la derecha, selector segmentado del origen
   («Local library | Torrent | Online»). Episodios: los tres primeros como tarjetas 16:9 grandes y luego lista con
   miniatura, «Episode 1 24m», título en negrita y descripción de 2 líneas.
7. **Modal (lista de reproducción)**. Panel centrado oscuro con borde sutil y esquinas ≈14 px; fondo de la página
   oscurecido y desenfocado. Campos con fondo #111 y borde fino. Filas de episodio con miniatura cuadrada, nombre en
   gris, «Episode 9» en blanco, chips de origen (elegido con borde). Botón principal a todo lo ancho en el color de
   acento.
8. **Inicio de manga**. Hero con la ilustración de la obra en curso, «Continue reading / Chainsaw Man». Fila de pósters
   con insignias en la base sobre degradado (capítulos leídos «218», «58/-», nota «★ 8» en píldora de color). Géneros
   como fila de texto centrada. Sección «Planning».
9. **Descubrir**. Hero con póster, título, géneros pequeños, nota en píldora, sinopsis y botón «Preview». Debajo, control
   segmentado centrado (Anime | Schedule | Manga) y botones-píldora con icono («Custom sources», «Advanced search»).
   «Trending Right Now» con fila de géneros en chips (el elegido con fondo) y flecha para desplazar; puntos de página a
   la derecha del título.
10. **Modal de Watch Party**. Mismo lenguaje: tarjeta interior con borde, botón de acento a todo lo ancho con icono.

## Patrones comunes

- **Color**: lienzo casi negro (#000–#0b0b0b), superficies un paso más claras (#121212–#171717), bordes muy sutiles
  (#1f1f1f–#262626), texto blanco y secundario gris neutro (#a1a1aa), un único color de acento usado con moderación
  (botón principal, píldora elegida, nota, insignias). Rojo solo para avisos y borrar.
- **Imágenes protagonistas**: banners a sangre con degradado hacia el fondo (de abajo y de la izquierda) para que el
  texto se lea sin cajas. Portadas desenfocadas como fondo de tarjetas (calendario).
- **Proporciones**: pósters 2:3 para obras, 16:9 para episodios. Rejillas de 6-8 columnas en escritorio, 2-3 en móvil.
- **Tipografía**: una sans geométrica limpia (tipo Inter); títulos de sección ≈24-28 px en 700; hero 40-48 px en 800;
  metadatos 12-13 px en gris. Mucha jerarquía con tamaño y peso, poca con color.
- **Espaciado**: márgenes generosos (≈32 px en escritorio, 16 px en móvil), 12-16 px entre tarjetas, 40-48 px entre
  secciones. Todo alineado a una columna que empieza justo después del rail.
- **Forma**: esquinas 8 px en tarjetas, 12-16 px en paneles y modales, píldoras completas en chips y segmentados.
- **Estados**: hover que aclara la superficie o acerca la imagen; elegido con fondo de píldora; foco visible; botones
  secundarios oscuros semitransparentes sobre imágenes. Carruseles con puntos discretos.
- **Navegación**: rail fijo a la izquierda en escritorio; las secciones se reconocen por icono; buscador y acciones
  arriba a la derecha.

## Qué lo hace parecer profesional

1. Restricción: casi todo es negro, gris y blanco; el acento aparece en pocos sitios y siempre con el mismo significado.
2. Imágenes grandes y bien recortadas, fundidas con degradados en lugar de enmarcadas.
3. Jerarquía tipográfica clara y constante entre pantallas.
4. Alineación estricta a una cuadrícula y espaciado consistente.
5. Controles pequeños y discretos que no compiten con el contenido; aparecen al pasar por encima.
6. Las mismas piezas (tarjeta, fila con título, píldora, panel) repetidas en todas las pantallas.

## Cómo lo aplica Yomukai

- Tokens únicos (`shared/src/main/resources/design/tokens.json`) para web (variables CSS) y app (MaterialTheme): lienzo
  #050505, superficies #111/#171717, borde #232323, texto #f4f4f5 / #a1a1aa, acento brasa #ff8a5b.
- Rail con iconos y texto en escritorio (más claro que solo iconos para un grupo nuevo), barra inferior en móvil,
  barra superior con búsqueda global (Ctrl+K), avisos y avatar.
- Inicio: hero en carrusel con banner de AniList y degradados; filas de pósters 2:3 y de «continuar» 16:9 con progreso.
- Ficha de obra: banner + póster + título grande + metadatos en gris + botón principal inteligente; pestañas.
- Calendario semanal con portadas difuminadas de fondo, como la referencia 4.
- Paneles/modales oscuros con borde sutil y botón de acento a todo lo ancho.
