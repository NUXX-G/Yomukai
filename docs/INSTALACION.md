# Instalar Yomukai

Yomukai son dos piezas:

- **El servidor**: la web, las cuentas, la API, las salas, el foro y el proxy de vídeo. Es ligero: cabe en un VPS de 2 GB compartido con otras cosas.
- **El worker** (opcional): hace lo pesado. Eso incluye las voces de calidad, el OCR de cómics, la traducción, los subtítulos con whisper, las huellas de audio para saltar el opening y las descargas al Jellyfin o al NAS. Se conecta él al servidor, así que no abre ningún puerto.

Sin worker todo sigue funcionando. Lo pesado se degrada con elegancia:

- las voces salen del móvil o del navegador;
- el opening se salta con +85 s;
- los subtítulos, la traducción y las descargas al NAS aparecen como «no disponible ahora».

## Elige cómo montarlo

| Forma | Para quién | Comando |
|---|---|---|
| **1. Solo el servidor** | Un VPS pequeño, o probarlo | `curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.sh \| bash` |
| **2. Todo en uno** | Una sola máquina con RAM y CPU de sobra (un mini PC, un servidor en casa) | `curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.sh \| YOMUKAI_MODE=todo-en-uno bash` |
| **3. VPS + worker en casa** | Un VPS público y un equipo en casa con Jellyfin/NAS (o un portátil con GPU) | La forma 1 en el VPS y, en casa, el instalador del worker (ver más abajo) |

Los tres necesitan Docker con Compose v2. Para actualizar, vuelve a ejecutar la misma línea; los ajustes de `.env` se conservan.

Al terminar, el instalador imprime una dirección `…/configurar?token=…`. Ábrela para crear tu cuenta de administrador: no hay contraseña por defecto y el código solo sirve una vez.

### 2. Todo en uno: qué hace

- Añade al `.env` lo siguiente:
  - `COMPOSE_PROFILES=todo-en-uno`;
  - un `LOCAL_WORKER_TOKEN` aleatorio;
  - los límites del worker, `WORKER_MEMORY=2g` y `WORKER_CPUS=1.5`.
- El servidor registra ese token al arrancar como el worker «este servidor». No hay que copiar nada a mano.
- El worker habla con el servidor por la red interna de Docker (`http://yomukai:8080`) y nunca sale a internet para eso.
- Tiene prioridad baja (`cpu_shares`), sus propios límites y se aparta solo si la máquina va cargada. Así no le quita recursos al servidor.
- **Carpetas de Jellyfin y cómics en la misma máquina**: descomenta las líneas `volumes` del servicio `worker` en `docker-compose.yml`. Pon esas rutas (las de dentro del contenedor, p. ej. `/media/anime`) en Ajustes › Administración › Integraciones.

Para pasar una instalación existente a todo en uno, vuelve a lanzar el instalador con `YOMUKAI_MODE=todo-en-uno`. Para quitarlo, borra `COMPOSE_PROFILES` y `LOCAL_WORKER_TOKEN` del `.env`, ejecuta `docker compose up -d --remove-orphans` y revoca el worker en el panel.

### 3. VPS + worker en casa, paso a paso con Tailscale

Tailscale crea una red privada entre tus equipos. El VPS llega al Jellyfin de casa, el worker llega al VPS y nadie abre puertos en el router.

1. **Cuenta y equipos**: crea una cuenta gratuita en tailscale.com e instálalo en los dos equipos:
   ```bash
   curl -fsSL https://tailscale.com/install.sh | sh
   sudo tailscale up            # abre el enlace que imprime y autoriza el equipo
   tailscale ip -4              # la IP 100.x.y.z de cada equipo
   ```
2. **En el VPS**, instala Yomukai (forma 1). Si lo publicas con un dominio, ponlo detrás de Caddy o nginx con HTTPS y añade `TRUST_PROXY=true` y `PUBLIC_URL=https://…` al `.env`.
3. **Crea el token del worker** en la web: Ajustes › Administración › Workers › Nuevo worker. Cópialo: solo se muestra una vez.
4. **En casa**:
   ```bash
   curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install-worker.sh | \
     YOMUKAI_URL=http://100.x.y.z:8080 WORKER_TOKEN=ykw_... bash
   ```
   Usa la IP de Tailscale del VPS. El worker solo acepta `http://` dentro de Tailscale o de la red local; por internet exige `https://`. Con GPU NVIDIA añade `WORKER_GPU=true` (necesita `nvidia-container-toolkit`).
5. **Jellyfin**: en Ajustes › Administración › Integraciones pon `http://<IP Tailscale de casa>:8096` y una clave de API de Jellyfin (Panel › Claves de API). Pulsa «Probar Jellyfin desde el VPS». Las rutas de las bibliotecas son las que ve el worker; móntalas en `docker-compose.worker.yml`.
6. **Comprobar**: en el panel de admin el worker debe salir «en línea» con sus motores, y en Ajustes › Servidor de casa, «Encendido».

Si el equipo de casa se apaga, los trabajos esperan en la cola y lo demás sigue funcionando. Cuando vuelve, los recoge.

### Motores del worker (todos opcionales)

| Imagen / opción | Qué añade |
|---|---|
| `yomukai-worker` (por defecto) | ffmpeg (descargas al Jellyfin), Chromaprint (saltar opening/ending), Tesseract (OCR de cómics en español, inglés, japonés, coreano y chino) |
| `WORKER_FULL=true` → `yomukai-worker-full` | Además voces naturales con Pocket TTS (Kyutai, MIT) y subtítulos con whisper.cpp (MIT, modelo «base»). Imagen grande: mejor en el equipo de casa |
| `WORKER_TRANSLATION=true` | Contenedor de LibreTranslate (AGPL, aparte y sin modificar) para traducir bocadillos y frases sin conexión; descarga sus modelos la primera vez |

Ejemplo con todo: `curl -fsSL …/install-worker.sh | YOMUKAI_URL=… WORKER_TOKEN=… WORKER_FULL=true WORKER_TRANSLATION=true bash`. El worker anuncia solo los motores que encuentra; lo que falte se apaga con elegancia.

## Variables principales (`.env` del servidor)

| Variable | Qué hace | Por defecto |
|---|---|---|
| `PORT` | Puerto de la web | 8080 |
| `PUBLIC_URL` | Dirección pública (enlaces de invitación, avisos) | — |
| `TRUST_PROXY` | `true` detrás de Caddy/nginx/Traefik | false |
| `ADMIN_USER` / `ADMIN_PASSWORD` | Crea o restablece el admin al arrancar (si no, asistente de primera configuración) | admin / — |
| `GUESTS_ENABLED`, `GUEST_DAYS` | Invitados sin cuenta, limitados | false, 1 |
| `YOMUKAI_MEMORY`, `YOMUKAI_CPUS` | Límites del contenedor | 1g, 1.0 |
| `BANDWIDTH_CAP_GB` | Tope mensual de vídeo por el servidor (0 = solo medir) | 0 |
| `NAS_UPLOAD_MBPS` | Subida de casa: las salas con vídeo del NAS ajustan la calidad | 20 |
| `DISCORD_WEBHOOK_URL`, `NTFY_URL`, `NTFY_TOKEN` | Avisos al admin, salas abiertas e hilos nuevos | — |
| `BACKUP_HOUR`, `BACKUP_KEEP_DAYS`, `BACKUP_COPY_DIR` | Copias nocturnas de la base de datos | 4, 14, — |
| `COMPOSE_PROFILES`, `LOCAL_WORKER_TOKEN`, `WORKER_MEMORY`, `WORKER_CPUS` | Todo en uno | — |
| `MAL_CLIENT_ID` | Notas de MyAnimeList | — |
| `TMDB_API_KEY` | Fondos panorámicos de alta resolución para el anime (themoviedb.org, gratis) | — |
| `SOURCE_REPOS` | Repositorios extra de fuentes JSON firmadas: `https://…/bundle.json#CLAVE` (ver sources/README.md) | — |

Más detalles del VPS pequeño (swap de colchón, medir memoria, canario de fuentes, yt-dlp) en [VPS.md](VPS.md).
