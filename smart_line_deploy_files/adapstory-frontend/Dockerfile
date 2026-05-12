# syntax=docker/dockerfile:1
# Adapstory Frontend — Turborepo monorepo (pnpm)
#
# ARG APP_NAME  — which Next.js app to build and serve (default: admin)
# Future microfrontends: add packages/<name>/ and build with --build-arg APP_NAME=<name>
#
# Build:  docker build --build-arg APP_NAME=admin -t frontend:latest .
# Run:    docker run -p 3000:3000 frontend:latest

ARG APP_NAME=admin

# ── Stage 1: Install dependencies ──────────────────────────────────────────
FROM node:22-alpine3.21 AS deps
WORKDIR /app
RUN corepack enable

# Unset proxy env vars — npmjs.org is public internet, proxy causes ECONNRESET
# on bulk TLS connections. Kaniko pod-level proxy handles base image pulls.
ENV HTTP_PROXY="" HTTPS_PROXY="" http_proxy="" https_proxy=""

# pnpm network resilience: limit concurrency to avoid socket exhaustion,
# increase retries and timeout for flaky network conditions
RUN printf 'network-concurrency=4\nfetch-retries=5\nfetch-retry-mintimeout=20000\nfetch-retry-maxtimeout=120000\nfetch-timeout=60000\n' > /root/.npmrc

# Copy manifests only — maximises layer cache on source changes
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY packages/admin/package.json          ./packages/admin/package.json
COPY packages/block-editor/package.json   ./packages/block-editor/package.json
COPY packages/editor-plugin/package.json  ./packages/editor-plugin/package.json
COPY packages/landing/package.json        ./packages/landing/package.json
COPY packages/school/package.json         ./packages/school/package.json
COPY packages/student/package.json        ./packages/student/package.json
COPY packages/eslint-config/package.json  ./packages/eslint-config/package.json
COPY packages/prettier-config/package.json ./packages/prettier-config/package.json
COPY packages/tsconfig/package.json       ./packages/tsconfig/package.json
COPY packages/i18n/package.json          ./packages/i18n/package.json
COPY packages/shared/package.json        ./packages/shared/package.json

RUN corepack install && pnpm install --frozen-lockfile

# ── Stage 2: Build ──────────────────────────────────────────────────────────
FROM node:22-alpine3.21 AS builder
ARG APP_NAME
# NEXT_PUBLIC_* vars are inlined at build time (SSG bake-in).
# Pass via --build-arg in CI (Jenkins buildArgs) for each environment.
# Landing requires: NEXT_PUBLIC_CTA_URL, NEXT_PUBLIC_SCHOOL_REGISTER_URL
ARG NEXT_PUBLIC_CTA_URL
ARG NEXT_PUBLIC_SCHOOL_REGISTER_URL
ARG NEXT_PUBLIC_BASE_URL
# BFF URLs are evaluated at next build time in rewrites() — must be baked into the image
ARG BFF_URL
ARG BFF_SCHOOL_URL
ENV NEXT_PUBLIC_CTA_URL=${NEXT_PUBLIC_CTA_URL}
ENV NEXT_PUBLIC_SCHOOL_REGISTER_URL=${NEXT_PUBLIC_SCHOOL_REGISTER_URL}
ENV NEXT_PUBLIC_BASE_URL=${NEXT_PUBLIC_BASE_URL}
ENV BFF_URL=${BFF_URL}
ENV BFF_SCHOOL_URL=${BFF_SCHOOL_URL}
WORKDIR /app
RUN corepack enable
ENV HTTP_PROXY="" HTTPS_PROXY="" http_proxy="" https_proxy=""

# Restore installed deps (pnpm uses virtual store in root node_modules)
COPY --from=deps /app/node_modules ./node_modules
# Restore package-level node_modules symlinks so workspace-local binaries like next are resolvable.
COPY --from=deps /app/packages ./packages

# Copy full source
COPY . .

# Some apps do not ship public/ or messages/; create empty placeholders so runtime COPY stays stable.
RUN mkdir -p packages/${APP_NAME}/public packages/${APP_NAME}/messages

# Build only the selected app (turbo respects --filter)
RUN pnpm turbo run build --filter="@adapstory/${APP_NAME}"

# ── Stage 3: Runtime (Next.js standalone) ───────────────────────────────────
FROM node:22-alpine3.21 AS runner
ARG APP_NAME
# Expose ARG as ENV so CMD can reference it at runtime
ENV APP_NAME=${APP_NAME}
ENV NODE_ENV=production
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

WORKDIR /app

RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nextjs

# Next.js standalone output for monorepo mirrors the repo tree:
#   packages/<app>/.next/standalone/  ← server.js + traced node_modules
COPY --from=builder /app/packages/${APP_NAME}/.next/standalone ./
COPY --from=builder /app/packages/${APP_NAME}/.next/static     ./packages/${APP_NAME}/.next/static

# Public assets (optional — not all apps have them)
COPY --from=builder /app/packages/${APP_NAME}/public           ./packages/${APP_NAME}/public

# i18n message files (loaded at runtime by next-intl)
COPY --from=builder /app/packages/${APP_NAME}/messages         ./packages/${APP_NAME}/messages

# Set correct runtime permissions for Next.js cache
RUN mkdir -p packages/${APP_NAME}/.next/cache && \
    chown -R nextjs:nodejs packages/${APP_NAME}/.next/cache

USER nextjs

EXPOSE 3000

# Use sh -c so $APP_NAME (runtime ENV) is expanded
CMD ["sh", "-c", "exec node packages/$APP_NAME/server.js"]
