FROM maven:3.8.8-eclipse-temurin-11 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /src/target/teplokontur-1.0.0.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx2g"
ENTRYPOINT ["java","-jar","/app/app.jar"]
