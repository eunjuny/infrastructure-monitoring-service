FROM node:22-alpine AS build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
ARG VITE_AUTH_ENABLED=false
ENV VITE_AUTH_ENABLED=$VITE_AUTH_ENABLED
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.28-alpine
ARG SOURCE_REVISION=unversioned
ARG BUILD_ID=local
LABEL org.opencontainers.image.revision=$SOURCE_REVISION io.infrastructure.build-id=$BUILD_ID
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
