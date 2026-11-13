# The Brainstorm Assistant's Observer task service. Build from the repository root:
#   docker build -f service/Dockerfile -t observer-service .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :service:installDist --no-daemon --console=plain

FROM eclipse-temurin:21-jre
COPY --from=build /src/service/build/install/observer-service /opt/observer-service
# No volume: the runs and the papers waiting are in Postgres.
USER 65534:65534
EXPOSE 8080
ENTRYPOINT ["/opt/observer-service/bin/observer-service"]
