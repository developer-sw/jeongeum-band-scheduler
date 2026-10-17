FROM node:24.18.0-bookworm-slim@sha256:6f7b03f7c2c8e2e784dcf9295400527b9b1270fd37b7e9a7285cf83b6951452d AS base

WORKDIR /app

FROM base AS dependencies

COPY package.json package-lock.json .npmrc ./
RUN --mount=type=cache,target=/root/.npm,sharing=locked \
    npm ci --no-audit --no-fund --maxsockets=5 --fetch-retries=3 --fetch-timeout=120000

FROM base AS builder

# This is an internal server origin, never a browser value or a secret.
ARG APP_API_ORIGIN=http://backend:8080
ENV APP_API_ORIGIN=${APP_API_ORIGIN} \
    NEXT_TELEMETRY_DISABLED=1

COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM base AS runner

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=3000

COPY --from=builder --chown=node:node /app/public ./public
COPY --from=builder --chown=node:node /app/.next/standalone ./
COPY --from=builder --chown=node:node /app/.next/static ./.next/static
RUN mkdir -p .next/cache && chown node:node .next/cache

USER node
EXPOSE 3000

# Next process health only; Spring's database readiness is checked separately.
HEALTHCHECK --interval=30s --timeout=6s --start-period=30s --retries=3 \
    CMD ["node", "--input-type=commonjs", "-e", "const http = require('node:http'); const req = http.get({ host: '127.0.0.1', port: process.env.PORT || 3000, path: '/', timeout: 4000 }, (res) => { res.resume(); process.exit(res.statusCode === 200 ? 0 : 1); }); req.on('timeout', () => { req.destroy(); process.exit(1); }); req.on('error', () => process.exit(1));"]

CMD ["node", "server.js"]
