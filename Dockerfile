FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S dora && adduser -S dora -G dora
COPY --from=builder /build/target/*.jar app.jar
USER dora
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
