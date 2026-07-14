# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:23-jdk-alpine AS runtime-builder

WORKDIR /custom-jre

COPY ./.docker-artifacts/dependencies.txt .
RUN --mount=type=cache,target=/var/cache/apk \
    apk add --update-cache binutils && \
    jlink \
        --add-modules $(cat dependencies.txt) \
        --output jre \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=2 && \
    apk del binutils

FROM alpine:3.18.12 AS final

WORKDIR /app

ARG TARGETARCH
ENV JAVA_HOME=/jre \
    PATH="/jre/bin:$PATH" \
    LANG=C.UTF-8 \
    NGINX_PORT=80

RUN --mount=type=cache,target=/var/cache/apk \
    addgroup -S tf && \
    adduser -S -G tf tf && \
    apk add --update-cache nginx wget curl unzip tini su-exec gettext openssl3 libstdc++ gcompat libc6-compat && \
    rm -rf /tmp/* /var/tmp/* && \
    touch /run/nginx.pid && \
    chown -R tf:tf /app /etc/nginx /var/lib/nginx /var/log/nginx /run/nginx.pid && \
    printf '#!/bin/sh\njava -Djava.library.path=/app/tdlib -cp /app/api.jar telegram.files.Maintain "$@"\n' > /usr/bin/tfm && \
    chmod +x /usr/bin/tfm

COPY --from=runtime-builder --chown=tf:tf /custom-jre/jre /jre
COPY --chown=tf:tf ./.docker-artifacts/api.jar /app/api.jar
COPY --chown=tf:tf ./.docker-artifacts/web/ /app/web/

COPY --chown=tf:tf ./tdlib/linux_$TARGETARCH /app/tdlib
COPY --chown=tf:tf ./entrypoint.sh .
COPY --chown=tf:tf ./nginx.conf.template /etc/nginx/nginx.conf.template

EXPOSE $NGINX_PORT

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["/bin/sh", "./entrypoint.sh"]
