# =============================================================================
#  Dockerfile — Downstream Service
#  Build context: project root (API-Gateway/)
#  Multi-stage: Maven build → slim JRE runtime
# =============================================================================

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

# Cache Maven dependencies by copying POMs first
COPY pom.xml                ./pom.xml
COPY gateway/pom.xml        ./gateway/pom.xml
COPY downstream-service/pom.xml ./downstream-service/pom.xml
COPY config-server/pom.xml  ./config-server/pom.xml

# Install Maven
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

# Resolve dependencies
RUN mvn dependency:go-offline -pl downstream-service -am -q

# Copy downstream source and build
COPY downstream-service/src ./downstream-service/src

RUN mvn -f pom.xml package -pl downstream-service -am -DskipTests -q

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
# eclipse-temurin:17-jre-jammy has multi-arch support (amd64 + arm64/Apple Silicon)
FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

RUN groupadd -r downstream && useradd -r -g downstream downstream
USER downstream

COPY --from=builder /workspace/downstream-service/target/downstream-service-*.jar app.jar

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

EXPOSE 8081
