# The Brainstorm Assistant's Observer task service. Build from the repository root:
#   docker build -f service/Dockerfile -t observer-service .
# assistant-runner's :core comes from JitPack, which can't build it while that repository is
# private. Until then, put a checkout of it in the context and name it:
#   docker build -f service/Dockerfile --build-arg GRADLE_ARGS=-PassistantRunnerDir=vendor/assistant-runner -t observer-service .
FROM eclipse-temurin:21-jdk AS build
ARG GRADLE_ARGS=""
WORKDIR /src
COPY . .
RUN ./gradlew :service:installDist --no-daemon --console=plain $GRADLE_ARGS

FROM eclipse-temurin:21-jre
COPY --from=build /src/service/build/install/observer-service /opt/observer-service
# No volume: the runs and the papers waiting are in Postgres.
USER 65534:65534
EXPOSE 8080
ENTRYPOINT ["/opt/observer-service/bin/observer-service"]
