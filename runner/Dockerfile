# The Assistant's print worker. Build from the repository root:
#   docker build -f runner/Dockerfile -t observer-runner .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :runner:installDist --no-daemon --console=plain

FROM eclipse-temurin:21-jre
COPY --from=build /src/runner/build/install/observer-runner /opt/observer-runner
# The run records live here: mount a volume so a restart doesn't run a morning twice.
RUN mkdir -p /var/lib/observer-runner && chown 65534:65534 /var/lib/observer-runner
ENV OBSERVER_RUNNER_STATE=/var/lib/observer-runner/runs.json
VOLUME /var/lib/observer-runner
USER 65534:65534
ENTRYPOINT ["/opt/observer-runner/bin/observer-runner"]
