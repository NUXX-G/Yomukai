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

# 3. Runtime
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 yomukai && mkdir -p /data && chown yomukai /data
WORKDIR /app
COPY --from=server /src/server/build/install/server/ /app/
COPY --from=web /web/dist/ /app/web/
# The build stages leave files readable by root only; the app runs as its own user.
RUN chmod -R a+rX /app
ENV PORT=8080 DATA_DIR=/data WEB_DIR=/app/web JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
USER yomukai
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /auth/me HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q HTTP"]
CMD ["/app/bin/server"]
