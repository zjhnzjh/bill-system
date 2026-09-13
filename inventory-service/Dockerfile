FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -q -Dmaven.wagon.http.retryHandler.count=5 -pl inventory-service -am dependency:go-offline
COPY inventory-service/src inventory-service/src
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -q -Dmaven.wagon.http.retryHandler.count=5 -pl inventory-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/inventory-service/target/inventory-service-0.2.0-SNAPSHOT.jar app.jar
EXPOSE 8786
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
