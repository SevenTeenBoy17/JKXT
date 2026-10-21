# syntax=docker/dockerfile:1.7
# Temporary, synthetic-data validation only. Not a durable production topology.
# Build context MUST be the application directory (JKXT V2.0), not repository root.
FROM maven:3.9.9-eclipse-temurin-21 AS backend-build
WORKDIR /backend
COPY backend/pom.xml ./
COPY backend/src ./src
# Packaging is compilation evidence only. Tests have a separate isolation gate.
RUN --mount=type=cache,id=jkxt-render-maven,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -C -DskipTests package

FROM node:22-bookworm AS frontend-build
WORKDIR /frontend
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY Dashboard.html tailwind.config.cjs ./
COPY assets ./assets
COPY src ./src
COPY scripts/frontend-build.mjs ./scripts/frontend-build.mjs
RUN npm run build

FROM eclipse-temurin:21-jre-noble AS runtime
RUN apt-get update && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends bash nginx-light gettext-base tini \
       fontconfig fonts-dejavu-core ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* && rm -f /usr/bin/pebble \
    && groupadd --gid 10001 jkxt && useradd --uid 10001 --gid 10001 --no-create-home jkxt
WORKDIR /app
COPY --from=backend-build /backend/target/jkxt-backend-*.jar /app/jkxt-backend.jar
COPY --from=frontend-build /frontend/dist/ /app/www/
COPY deploy/render-free/entrypoint.sh deploy/render-free/nginx.conf.template /app/render-free/
RUN chmod 0555 /app/render-free/entrypoint.sh && chmod 0444 /app/render-free/nginx.conf.template
ENV PORT=10000 TZ=Asia/Shanghai
USER 10001:10001
EXPOSE 10000
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/bin/bash", "-o", "pipefail", "-c", "curl --fail --silent --show-error --max-time 3 \"http://127.0.0.1:${PORT}/actuator/health/readiness\" | grep -qx '{\"status\":\"UP\"}'"]
ENTRYPOINT ["/usr/bin/tini", "--", "/app/render-free/entrypoint.sh"]
