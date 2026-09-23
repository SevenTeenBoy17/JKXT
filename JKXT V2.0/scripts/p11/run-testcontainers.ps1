param(
  [string]$MavenImage = "maven:3.9.9-eclipse-temurin-21"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Backend = Join-Path $Root "backend"
$M2 = Join-Path $env:USERPROFILE ".m2"

$args = @(
  "run", "--rm",
  "-v", "${Backend}:/workspace",
  "-v", "${M2}:/root/.m2",
  "-v", "/var/run/docker.sock:/var/run/docker.sock",
  "-e", "DOCKER_HOST=unix:///var/run/docker.sock",
  "-e", "DOCKER_API_VERSION=1.47",
  "-e", "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock",
  "-e", "TESTCONTAINERS_RYUK_DISABLED=true",
  "-e", "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal",
  "-w", "/workspace",
  $MavenImage,
  "mvn",
  "-Dtest=P11TestcontainersInfrastructureTest",
  "-Djkxt.testcontainers.enabled=true",
  "test"
)

& docker @args
if ($LASTEXITCODE -ne 0) {
  throw "P11 Testcontainers verification failed"
}
