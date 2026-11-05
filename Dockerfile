# Yomukai web: the server (Kotlin, :shared sources) and the web app, in one small image.

# 1. Web app
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# 2. Server (no Android SDK here: YOMUKAI_SERVER_ONLY leaves the app module out)
FROM eclipse-temurin:21-jdk AS server
WORKDIR /src
ENV YOMUKAI_SERVER_ONLY=true GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.configuration-cache=false"
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN ./gradlew --version --console=plain -q
COPY shared/ shared/
COPY server/ server/
RUN ./gradlew :server:installDist --console=plain -q

# 3. Natural voices for reading novels aloud: Piper (open source, runs offline on this machine).
#    The voice models are downloaded by the server the first time each one is used.
FROM eclipse-temurin:21-jre AS piper
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*  && case "$TARGETARCH" in arm64) ARCH=aarch64 ;; arm) ARCH=armv7l ;; *) ARCH=x86_64 ;; esac  && curl -fsSL "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_$ARCH.tar.gz" | tar -xz -C /opt

# 4. Runtime
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 yomukai && mkdir -p /data && chown yomukai /data
WORKDIR /app
COPY --from=server /src/server/build/install/server/ /app/
COPY --from=web /web/dist/ /app/web/
COPY --from=piper /opt/piper/ /opt/piper/
# The build stages leave files readable by root only; the app runs as its own user.
RUN chmod -R a+rX /app /opt/piper
# JVM sized for a small VPS: the heap takes 55 % of the container limit (the rest is metaspace, threads, Netty
# buffers and Piper), Serial GC (least memory), and the process exits on OutOfMemory so Docker restarts it clean.
ENV PORT=8080 DATA_DIR=/data WEB_DIR=/app/web PIPER_PATH=/opt/piper/piper \
    JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=55 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -Xss512k -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication"
USER yomukai
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /auth/me HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q HTTP"]
CMD ["/app/bin/server"]
