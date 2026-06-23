# =============================================================================
#  Dockerfile — Config Server
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
RUN mvn dependency:go-offline -pl config-server -am -q

# Copy config-server source and build
COPY config-server/src ./config-server/src

RUN mvn -f pom.xml package -pl config-server -am -DskipTests -q

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

RUN groupadd -r configserver && useradd -r -g configserver configserver
USER configserver

COPY --from=builder /workspace/config-server/target/config-server-*.jar app.jar

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

EXPOSE 8888
