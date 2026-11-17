# JOVX backend image (e.g. Render "Docker" web service).
# Build:  docker build -t jovx-api .
# Tests are NOT run here: the PostgreSQL acceptance tests need the isolated local test server.
# Run `./mvnw verify` with that server before pushing.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/jovx-api-0.0.1-SNAPSHOT.jar app.jar

# Listen on all interfaces inside the container; Render supplies PORT at runtime.
ENV SERVER_ADDRESS=0.0.0.0 \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8080

# Required at runtime (never baked into the image):
#   DB_URL, DB_USERNAME, DB_PASSOWRD
#   JWT_PUBLIC_KEY_LOCATION=file:/etc/secrets/public.pem
#   JWT_PRIVATE_KEY_LOCATION=file:/etc/secrets/private.pem
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
