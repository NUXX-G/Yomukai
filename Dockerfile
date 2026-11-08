# Yomukai web: the server (Kotlin, :shared sources) and the web app, in one small image.

# 1. Web app
FROM node:24-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
# The design tokens are shared with the Android app; the build turns them into CSS variables.
COPY shared/src/main/resources/design/ /shared/src/main/resources/design/
COPY web/ ./
RUN npm run build

# 2. Server (no Android SDK here: YOMUKAI_SERVER_ONLY leaves the app module out)
FROM gradle:9.6.0-jdk25-noble AS server
WORKDIR /src
# Gradle 9.6.0 (the wrapper's version) comes in this image, and the Java 21 its toolchain asks for is copied from its
# own image: nothing is downloaded from GitHub while building, so its hiccups cannot break the images.
COPY --from=eclipse-temurin:21-jdk /opt/java/openjdk /opt/jdk21
ENV YOMUKAI_SERVER_ONLY=true GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.configuration-cache=false -Dorg.gradle.java.installations.paths=/opt/jdk21 -Dorg.gradle.java.installations.auto-download=false"
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN printf '\norg.gradle.java.installations.paths=/opt/jdk21\norg.gradle.java.installations.auto-download=false\n' >> gradle.properties
COPY shared/ shared/
COPY server/ server/
RUN for i in 1 2 3; do gradle :server:installDist --console=plain -q && exit 0; sleep $((i * 30)); done; exit 1

# 3. Natural voices for reading novels aloud: Piper (open source, runs offline on this machine).
#    The voice models are downloaded by the server the first time each one is used.
FROM eclipse-temurin:25-jre AS piper
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*  && case "$TARGETARCH" in arm64) ARCH=aarch64 ;; arm) ARCH=armv7l ;; *) ARCH=x86_64 ;; esac  && curl -fsSL "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_$ARCH.tar.gz" | tar -xz -C /opt

# 3b. yt-dlp (Unlicense), standalone build: only used to find the video URL behind a player page, never to download.
FROM eclipse-temurin:25-jre AS ytdlp
ARG TARGETARCH
ARG YTDLP_VERSION=2026.10.26
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/* \
 && case "$TARGETARCH" in \
      arm64) F=yt-dlp_linux_aarch64; SHA=e87e9533e89fe5889abaa21f5114dfe7756d6aa6219b83551add04d48b18e483 ;; \
      *) F=yt-dlp_linux; SHA=767ba9dce7052aa196f8b12652f433d76de128ba168966fa84b6394d5e6c6795 ;; \
    esac \
 && curl -fsSL -o /opt/yt-dlp "https://github.com/yt-dlp/yt-dlp/releases/download/$YTDLP_VERSION/$F" \
 && echo "$SHA  /opt/yt-dlp" | sha256sum -c - && chmod 755 /opt/yt-dlp

# 4. Runtime
FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --uid 10001 yomukai && mkdir -p /data && chown yomukai /data
WORKDIR /app
COPY --from=server /src/server/build/install/server/ /app/
COPY --from=web /web/dist/ /app/web/
COPY --from=piper /opt/piper/ /opt/piper/
COPY --from=ytdlp /opt/yt-dlp /opt/yt-dlp
# The build stages leave files readable by root only; the app runs as its own user.
RUN chmod -R a+rX /app /opt/piper
# JVM sized for a small VPS: the heap takes 55 % of the container limit (the rest is metaspace, threads, Netty
# buffers and Piper), Serial GC (least memory), and the process exits on OutOfMemory so Docker restarts it clean.
ENV PORT=8080 DATA_DIR=/data WEB_DIR=/app/web PIPER_PATH=/opt/piper/piper YTDLP_PATH=/opt/yt-dlp \
    JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=55 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -Xss512k -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication"
USER yomukai
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /auth/me HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q HTTP"]
CMD ["/app/bin/server"]
