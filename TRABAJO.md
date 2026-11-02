# TRABAJO — traspaso de la tanda actual

Léelo primero si retomas esto. `PLAN.md` es el histórico de fases; este fichero es lo que se está haciendo ahora.

## Reglas de trabajo
- Nada de `colmena`: se trabaja en este repo y se sube a `main`. Commits pequeños y push frecuente.
- Esta máquina **sí compila**: JDK en `C:\jdk-21.0.7`, SDK en `%LOCALAPPDATA%\Android\Sdk`.
- Emuladores: `Medium_Phone_API_37.0`, `Yomu_API33`, `Yomu_API26`.

```bash
export JAVA_HOME=/c/jdk-21.0.7 ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew testDebugUnitTest assembleDebug --console=plain
```

## Encargo del usuario (en orden)
1. Arreglar los fallos detectados en la revisión (lista abajo).
2. Dos fuentes nuevas: `m.webtoons.com` y `zonatmo.org`.
3. Comprobar que todas las fuentes funcionan y que el scraping va fino en todos los Android.
4. Scraping más rápido y fiable: tarda mucho y a veces no carga con la web bien.
5. Anime: quitar el «página 145 de 14124242».
6. Navegador integrado: atrás/adelante, más opciones, que vaya bien.
7. Cuando todo eso esté, buscar errores y dejar la app bien del todo.

## Estado
- [ ] Fuga de Tor por el WebView de Cloudflare
- [ ] Posición del episodio anterior guardada en el siguiente (se marca visto solo)
- [ ] Pantalla negra del reproductor: sin `onPlayerError`
- [ ] Búsqueda: al borrar el texto no vuelve historial/sugerencias
- [ ] Búsqueda: anime escondido bajo el manga del mismo título
- [ ] Anime: texto «Página X de Y»
- [ ] Favoritos: «Leyendo» se guarda como estado de publicación
- [ ] «Solo Wi-Fi» no frena un MP4 en curso
- [ ] Fallback de Cloudflare devuelve HTML en APIs JSON
- [ ] Dependencias sin usar y README
- [ ] Fuente WEBTOON
- [ ] Fuente ZonaTMO
- [ ] Rendimiento de red
- [ ] Navegador integrado
- [ ] Diagnóstico real de fuentes en emuladores API 26/33/37
- [ ] Revisión final de errores

Descartado: fallo de notificaciones con la app cerrada; el usuario confirma que funcionan.

## Hallazgos técnicos
- MangaDex: con User-Agent de navegador la API redirige a `/edge/challenge`; hay que usar el UA propio de la fuente.
- Royal Road: 403 «Just a moment» a clientes simples; depende del WebView.
- LectorManga y SurtrScans: sin respuesta desde esta red (`000`).
- WEBTOON:
  - episodios en `/api/v1/webtoon/{titleNo}/episodes?pageSize=30&cursor=N`
  - páginas en `var imageList` del visor, que exigen `Referer` (403 sin él)
  - búsqueda y catálogo por géneros en `www.webtoons.com/...?webtoon-platform-redirect=true`
- ZonaTMO:
  - catálogo y búsqueda en `/biblioteca?title=&_pg=N`
  - capítulos en `li.upload-link` dentro de la ficha
  - páginas en `.reader-img-wrap img`

## Causas de lentitud identificadas
- El limitador de 300 ms por servidor también frena las imágenes (Coil usa el mismo cliente).
- `CloudflareResolver` toma `challenge-platform` como desafío, y ese script sale en páginas normales: espera los 15 s.
- Timeouts largos con reintentos: un sitio caído tarda casi un minuto en fallar.
- DNS sobre HTTPS sin caché y con 10 s de espera si 1.1.1.1 no responde.
- LectorTMO parsea su HTML de 1,6 MB dos veces.
- Explorar no guarda nada entre aperturas.
