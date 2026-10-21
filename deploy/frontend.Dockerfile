FROM node:22-bookworm AS build
WORKDIR /frontend
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY Dashboard.html tailwind.config.cjs ./
COPY assets ./assets
COPY src ./src
COPY scripts/frontend-build.mjs ./scripts/frontend-build.mjs
RUN npm run build

# 1.27 is end-of-life (frozen on Alpine 3.21.3: 2 CRITICAL/41 HIGH). 1.30 is the current stable line (Trivy 0 HIGH/CRITICAL
# on 2026-10-16); apk upgrade picks up Alpine fixes published after the upstream build. Pinned tag for reproducibility.
FROM nginx:1.30.6-alpine
RUN apk upgrade --no-cache
COPY --from=build /frontend/dist/ /usr/share/nginx/html/
