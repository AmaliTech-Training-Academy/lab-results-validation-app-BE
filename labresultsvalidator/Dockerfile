# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create a dedicated non-root user/group and run the app as it.
RUN addgroup -S spring && adduser -S spring -G spring

# Copy the jar with non-root ownership.
COPY --from=builder --chown=spring:spring /app/target/*.jar app.jar

USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]