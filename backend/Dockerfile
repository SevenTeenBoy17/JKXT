FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
# Resolve only the real packaging lifecycle. go-offline also resolves unused plugin
# dependency trees; a transient download then invalidates the whole build layer.
# Keep only public artifacts in a locked cache (never workstation credentials).
# -C rejects checksum mismatches. Packaging is not a substitute for the test gate.
RUN --mount=type=cache,id=jkxt-maven-repository,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -C -DskipTests package

FROM eclipse-temurin:21-jre
# Headless captcha rasterization needs a real font in the Linux runtime, not just on dev PCs.
# Pull current Ubuntu security updates (e.g. curl) and drop the base image's unused pebble binary (unpackaged Go service manager; not our entrypoint).
RUN apt-get update && apt-get upgrade -y --no-install-recommends && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && rm -rf /var/lib/apt/lists/* && rm -f /usr/bin/pebble
WORKDIR /app
COPY --from=build /workspace/target/jkxt-backend-*.jar /app/jkxt-backend.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/jkxt-backend.jar"]
