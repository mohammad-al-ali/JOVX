# JOVX backend image (e.g. Render "Docker" web service).
# Build:  docker build -t jovx-api .
# Tests are NOT run here: the PostgreSQL acceptance tests need the isolated local test server.
# Run `./mvnw verify` with that server before pushing.

# Official Maven image: no wrapper download inside the build (the wrapper's
# distribution download failed its checksum check on Render's builder).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/jovx-api-0.0.1-SNAPSHOT.jar app.jar

# Listen on all interfaces inside the container; Render supplies PORT at runtime.
ENV SERVER_ADDRESS=0.0.0.0 \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8080

# Required at runtime (never baked into the image):
#   DB_URL, DB_USERNAME, DB_PASSWORD
#   JWT_PUBLIC_KEY_LOCATION=file:/etc/secrets/public.pem
#   JWT_PRIVATE_KEY_LOCATION=file:/etc/secrets/private.pem
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
