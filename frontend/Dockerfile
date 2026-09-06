FROM node:24-alpine AS dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:24-alpine AS builder
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=3000
RUN addgroup --system --gid 10001 sova && adduser --system --uid 10001 --ingroup sova sova
COPY --from=builder --chown=sova:sova /app/.next/standalone ./
COPY --from=builder --chown=sova:sova /app/.next/static ./.next/static
COPY --from=builder --chown=sova:sova /app/public ./public
USER sova
EXPOSE 3000
CMD ["node", "server.js"]
