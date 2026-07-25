# syntax=docker/dockerfile:1

# --- Build stage -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source so `docker compose up --build`
# doesn't re-download the internet on every code change.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# --- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin timelord
USER timelord

COPY --from=build /build/target/timelord-controller-*.jar /app/timelord-controller.jar

EXPOSE 8080
EXPOSE 45821/udp

ENTRYPOINT ["java", "-jar", "/app/timelord-controller.jar"]
