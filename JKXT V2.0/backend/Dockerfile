FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/jkxt-backend-*.jar /app/jkxt-backend.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/jkxt-backend.jar"]
