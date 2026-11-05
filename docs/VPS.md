# Yomukai en un VPS pequeño (2 GB, compartido)

Yomukai en el VPS solo hace lo ligero: API, cuentas, web, salas, foro, proxy de vídeo y el canario de fuentes.
Lo pesado (voces, OCR, subtítulos, huellas de audio, descargas a Jellyfin) lo hace el **worker** en casa
(`docker-compose.worker.yml`) o el propio móvil/navegador. Si el worker está apagado, esas funciones esperan en
la cola o salen como «no disponible ahora», sin romper nada.

## Instalar

```bash
# En el VPS
curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.sh | bash

# En el servidor de casa (token: Ajustes › Administración › Workers › Nuevo worker)
curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install-worker.sh | \
  YOMUKAI_URL=https://yomukai.tudominio.com WORKER_TOKEN=ykw_... bash
# Portátil con GPU NVIDIA: lo mismo con WORKER_GPU=true (necesita nvidia-container-toolkit).
```

## Que el portfolio nunca note a Yomukai

`docker-compose.yml` ya trae:

| Límite | Valor por defecto | Variable en `.env` |
|---|---|---|
| Memoria del contenedor | 1 GB (heap de la JVM = 55 %) | `YOMUKAI_MEMORY` |
| Memoria + swap | 1,5 GB | `YOMUKAI_MEMORY_SWAP` |
| CPU | 1 núcleo | `YOMUKAI_CPUS` |
| FlareSolverr (Chrome) | 512 MB, 0,5 núcleos | `FLARESOLVERR_MEMORY`, `FLARESOLVERR_CPUS` |
| Procesos | 512 | — |
| Logs | 3 × 10 MB | — |

- `oom_score_adj: 500` (y 800 para FlareSolverr): si el sistema se quedara sin memoria, el kernel mata antes a
  Yomukai que al portfolio. Docker lo vuelve a arrancar solo (`restart: unless-stopped`) y la JVM sale limpia con
  `-XX:+ExitOnOutOfMemoryError` en vez de quedarse medio viva.
- Las cachés en memoria se dimensionan solas según el heap (`MemoryBudget`); las de disco tienen tope
  (`IMAGE_CACHE_MB`, `RESULT_CACHE_MB`, 2 GB cada una por defecto).
- Para dar aún más prioridad al portfolio, pon en su servicio `oom_score_adj: -500`.

## Swap de colchón (1-2 GB)

Sin swap, un pico de memoria mata procesos al momento. Con un poco de swap el sistema aguanta el pico (más lento)
y los límites de arriba hacen el resto.

```bash
sudo fallocate -l 2G /swapfile        # o 1G si el disco va justo
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
# Que solo use swap cuando de verdad falte memoria:
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf && sudo sysctl --system
free -h   # comprobar
```

## Medir la memoria y la velocidad

```bash
docker stats --no-stream yomukai-yomukai-1     # RAM y CPU reales del contenedor
curl -s -b cookies.txt https://tu-dominio/api/admin/status   # heap, RSS y tráfico del mes (como admin)
# Medición completa (RAM en reposo y tras carga, p95 de la API):
git clone --depth 1 https://github.com/NUXX-G/Yomukai && cd Yomukai
BENCH_USER=admin BENCH_PASSWORD=... bash scripts/bench.sh https://tu-dominio "$(docker inspect -f '{{.State.Pid}}' yomukai-yomukai-1)"
```

Las mediciones de cada fase están en `TRABAJO.md` (tabla «Presupuesto de rendimiento»).

## Ancho de banda

El vídeo que pasa por el VPS (`/api/stream`, solo para servidores que lo necesitan, p. ej. los ligados a la IP)
se cuenta por mes. Con `BANDWIDTH_CAP_GB` > 0 llega un aviso al 80 % y al 100 % (Discord/ntfy), y al 100 % el
proxy deja de servir vídeo a quien no es admin hasta el mes siguiente; los servidores directos siguen funcionando.
Se puede desactivar el corte con el flag «Tope de tráfico de vídeo» del panel.

## Tailscale (VPS ↔ casa)

El worker se conecta **hacia fuera** al VPS: no hace falta abrir puertos en casa. Puede usar la dirección pública
(`https://…`) o, si el VPS está en tu tailnet, su IP de Tailscale (`http://100.x.y.z:8080`; el worker solo acepta
`http://` en direcciones de Tailscale o de red local).

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up            # en el VPS y en el servidor de casa
tailscale ip -4              # la IP 100.x del VPS, para YOMUKAI_URL
```

Para que el VPS hable con Jellyfin (Fase 10), el VPS tiene que estar en la misma tailnet.
