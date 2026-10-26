# syntax=docker/dockerfile:1
# Immagini della demo locale: backend Java 21 con qpdf 12.4.2 e server web con il frontend compilato.
# Tag e digest delle immagini di base sono fissati; qpdf è compilato dal sorgente ufficiale verificato.

ARG TEMURIN_JRE=eclipse-temurin:21.0.12.1_1-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
ARG MAVEN=maven:3.10.0-eclipse-temurin-21-noble@sha256:1bc7bb8342cb0cad1727af35ab5a36ce0abf13ec4e1393a001c5f0a559fcc1c7
ARG NODE=node:22.23.4-alpine3.24@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914
ARG NGINX=nginxinc/nginx-unprivileged:1.30.6-alpine3.24@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
ARG MYSQL=mysql:8.4.13@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69

# MySQL con schema e inizializzazione della demo; lo script non è eseguibile perché l'entrypoint lo carica con source.
FROM ${MYSQL} AS mysql
COPY database/schema.sql /medbook/schema.sql
COPY --chmod=0644 docker/mysql/10-medbook.sh /docker-entrypoint-initdb.d/10-medbook.sh

# qpdf: tarball della release v12.4.2 (firma di Jay Berkenbilt, chiave C2C96B10011FE009E6D1DF828A75D10998012C7E,
# verificata in preparazione); qui si controlla lo SHA-256 pubblicato nel file firmato della release.
FROM ${TEMURIN_JRE} AS qpdf
ARG QPDF_VERSION=12.4.2
ARG QPDF_SHA256=8a58af5b6141319287c1883bec8bd1bd545b7567b7fc5e6ce5d25a1c85f36397
# Compilazioni C++ parallele limitate: la memoria della VM Docker è spesso ridotta.
ARG QPDF_JOBS=2
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential cmake curl ca-certificates libjpeg-turbo8-dev zlib1g-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN curl -fsSL -o qpdf.tar.gz "https://github.com/qpdf/qpdf/releases/download/v${QPDF_VERSION}/qpdf-${QPDF_VERSION}.tar.gz" \
 && echo "${QPDF_SHA256}  qpdf.tar.gz" | sha256sum -c - \
 && tar -xzf qpdf.tar.gz --strip-components=1 \
 && rm qpdf.tar.gz
# Libreria statica e crittografia nativa: l'eseguibile dipende solo da zlib e libjpeg di sistema.
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON \
        -DBUILD_DOC=OFF -DUSE_IMPLICIT_CRYPTO=OFF -DREQUIRE_CRYPTO_NATIVE=ON \
 && cmake --build build --target qpdf --parallel "${QPDF_JOBS}" \
 && build/qpdf/qpdf --version | grep -qx "qpdf version ${QPDF_VERSION}"

FROM ${MAVEN} AS java
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory=/build/lib
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -q compile
# Caricamento dei dati dimostrativi: compilato a parte, usa soltanto le classi applicative.
COPY docker/demo demo
RUN find demo -name '*.java' > sorgenti.txt \
 && javac -encoding UTF-8 --release 21 -d /build/demo-classes -cp "target/classes:lib/*" @sorgenti.txt

FROM ${TEMURIN_JRE} AS backend
RUN apt-get update \
 && apt-get install -y --no-install-recommends libjpeg-turbo8 zlib1g \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 medbook \
 && useradd --system --uid 10001 --gid medbook --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin medbook \
 && install -d -m 0755 /opt/medbook /var/lib/medbook \
 && install -d -o medbook -g medbook -m 0700 /var/lib/medbook/referti
COPY --from=qpdf /src/build/qpdf/qpdf /usr/local/bin/qpdf
COPY --from=qpdf /src/LICENSE.txt /src/NOTICE.md /usr/local/share/doc/qpdf/
COPY --from=java /build/lib /opt/medbook/lib
COPY --from=java /build/target/classes /opt/medbook/classes
COPY --from=java /build/demo-classes /opt/medbook/demo
COPY --chmod=0755 docker/backend/medbook.sh /usr/local/bin/medbook
COPY --chmod=0755 docker/backend/healthcheck.sh /usr/local/bin/medbook-healthcheck
ENV LANG=C.UTF-8 TZ=Europe/Rome
WORKDIR /opt/medbook
USER medbook:medbook
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 CMD ["/usr/local/bin/medbook-healthcheck"]
ENTRYPOINT ["/usr/local/bin/medbook"]
CMD ["server"]

FROM ${NODE} AS frontend
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/index.html frontend/vite.config.js ./
COPY frontend/src src
RUN npm run build

FROM ${NGINX} AS web
COPY docker/web/default.conf /etc/nginx/conf.d/default.conf
COPY --from=frontend /build/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=6 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ && wget -q -O /dev/null http://127.0.0.1:8080/api/prestazioni || exit 1
