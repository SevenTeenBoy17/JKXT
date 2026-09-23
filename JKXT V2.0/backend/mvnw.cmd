@echo off
setlocal

set IMAGE=maven:3.9.9-eclipse-temurin-21
docker run --rm -v "%CD%:/workspace" -v "%USERPROFILE%\.m2:/root/.m2" -w /workspace %IMAGE% mvn %*
set EXIT_CODE=%ERRORLEVEL%

endlocal & exit /b %EXIT_CODE%
