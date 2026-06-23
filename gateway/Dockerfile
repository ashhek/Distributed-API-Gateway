# =============================================================================
#  Dockerfile — API Gateway (Spring Cloud Gateway)
#  Build context: project root (API-Gateway/)
#  Multi-stage: Maven build → slim JRE runtime
# =============================================================================

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

# Cache Maven dependencies by copying POMs first
# (Docker layer cache is invalidated only when POMs change)
COPY pom.xml                ./pom.xml
COPY gateway/pom.xml        ./gateway/pom.xml
COPY downstream-service/pom.xml ./downstream-service/pom.xml
COPY config-server/pom.xml  ./config-server/pom.xml

# Install Maven (not bundled in eclipse-temurin images)
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

# Resolve all dependencies (populates .m2 cache layer)
RUN mvn dependency:go-offline -pl gateway -am -q

# Copy gateway source and build
COPY gateway/src ./gateway/src

RUN mvn -f pom.xml package -pl gateway -am -DskipTests -q

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
# eclipse-temurin:17-jre-jammy has multi-arch support (amd64 + arm64/Apple Silicon)
FROM eclipse-temurin:17-jre-jammy AS runtime

WORKDIR /app

# Non-root user for security hardening
RUN groupadd -r gateway && useradd -r -g gateway gateway
USER gateway

# Copy the executable fat JAR from the build stage
COPY --from=builder /workspace/gateway/target/gateway-*.jar app.jar

# JVM flags tuned for containers:
#   UseContainerSupport   — read cgroup memory/CPU limits (not host values)
#   MaxRAMPercentage=75   — heap = 75% of container memory limit
#   urandom               — faster SecureRandom seeding in containers
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

EXPOSE 8080
