# Two stages, so the runtime image carries a jar and a JRE and nothing else.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Poms first. A change to a source file then reuses this layer, which is the difference between a
# rebuild that resolves the dependency tree and one that does not.
COPY pom.xml .
COPY cairn-core/pom.xml cairn-core/
COPY cairn-codec/pom.xml cairn-codec/
COPY cairn-store/pom.xml cairn-store/
COPY cairn-effects/pom.xml cairn-effects/
COPY cairn-testkit/pom.xml cairn-testkit/
COPY cairn-server/pom.xml cairn-server/
RUN mvn -B -ntp -q dependency:go-offline || true

COPY . .
# Tests run in CI against the matrix, not here: an image build that also runs the suite is an image
# build that takes four times as long and tells you something you already know.
RUN mvn -B -ntp -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S cairn && adduser -S -G cairn cairn

# The data directory is created and owned before the user is dropped, because a registry that has
# to run as root to create its own directory is a registry that runs as root.
RUN mkdir -p /var/lib/cairn && chown cairn:cairn /var/lib/cairn
VOLUME /var/lib/cairn

COPY --from=build /src/cairn-server/target/cairn-server-0.1.0.jar /opt/cairn/cairn.jar
USER cairn
WORKDIR /var/lib/cairn

EXPOSE 9080

# /readyz rather than /healthz: the two answer different questions, and the one a load balancer
# wants is whether the registry can serve a read.
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=5 \
    CMD wget -qO- http://127.0.0.1:9080/readyz >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/opt/cairn/cairn.jar"]
CMD ["--data-dir=/var/lib/cairn", "--address=0.0.0.0", "--port=9080"]
