# syntax=docker/dockerfile:1

# ==============================================================================
# Builder
# ==============================================================================
FROM node:24-alpine AS builder
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ==============================================================================
# Runtime - static files served by nginx, with /bff/ proxied to the BFF
# service so the browser only ever talks to this one origin.
# ==============================================================================
FROM nginx:alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist/angular-ui/browser /usr/share/nginx/html

EXPOSE 4200
