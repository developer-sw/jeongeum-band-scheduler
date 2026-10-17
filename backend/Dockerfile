FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b AS builder

# Use Ubuntu's signature-verified Java 21 packages on the pinned base. An unavailable
# fixed package revision must fail the build rather than silently selecting another JDK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jdk-headless=21.0.12.1+1-1~22.04.4 \
    && ln -s "$(dirname "$(dirname "$(readlink -f /usr/bin/javac)")")" /opt/build-jdk \
    && /opt/build-jdk/bin/javac -version \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/opt/build-jdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties gradle.lockfile ./
COPY gradle/wrapper/ ./gradle/wrapper/
COPY src/main/ ./src/main/
RUN --mount=type=cache,id=jeongeum-backend-gradle,target=/root/.gradle,sharing=locked \
    chmod 0755 gradlew \
    && sed -i 's/^networkTimeout=.*/networkTimeout=60000/; s/^retries=.*/retries=2/; s/^retryBackOffMs=.*/retryBackOffMs=1000/' gradle/wrapper/gradle-wrapper.properties \
    && ./gradlew --no-daemon -Dorg.gradle.internal.http.connectionTimeout=30000 -Dorg.gradle.internal.http.socketTimeout=60000 bootJar \
    && test -s build/libs/jeongeum-band-scheduler-backend-0.0.1-SNAPSHOT.jar \
    && sha256sum build/libs/jeongeum-band-scheduler-backend-0.0.1-SNAPSHOT.jar

FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 jeongeum \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin jeongeum

WORKDIR /opt/app
COPY --from=builder --chown=10001:10001 /workspace/build/libs/jeongeum-band-scheduler-backend-0.0.1-SNAPSHOT.jar ./app.jar
COPY --chmod=0555 docker-entrypoint.sh /usr/local/bin/jeongeum-backend

ENV SPRING_PROFILES_ACTIVE=production \
    LANG=C.UTF-8 \
    TZ=UTC
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=20s --timeout=6s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error --max-time 5 --output /dev/null "http://127.0.0.1:${APP_SERVER_PORT:-8080}/api/v1/health" || exit 1
ENTRYPOINT ["/usr/local/bin/jeongeum-backend"]
