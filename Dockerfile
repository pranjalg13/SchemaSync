# Production image: the built React app served by Spring Boot, as ONE service.
#
# Same origin means no dev-server proxy and no CORS, and it fits a single free-tier web service.
# (docker-compose.yml uses backend/Dockerfile + web/Dockerfile instead, for hot reload in dev.)

# ---- 1. build the UI --------------------------------------------------------------------------
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---- 2. build the API, with the UI baked into its static resources ---------------------------
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /app
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -q -B package -DskipTests

# ---- 3. run -----------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=api /app/target/schemasync-0.1.0.jar app.jar
USER app

# Tuned for a 512MB free-tier instance: cap the heap as a fraction of the container limit, use the
# serial collector (the lowest overhead for one small app), and stop JIT at C1 for a faster cold
# start -- free instances sleep when idle, so startup time is what visitors actually wait for.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
ENV SCHEMASYNC_DEMO_SEED_ORDERS=200000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
