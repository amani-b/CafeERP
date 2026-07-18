# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy only the POM first to leverage Docker layer caching for dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -q || true

# Copy the rest of the source and build
COPY src ./src
RUN mvn -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine

# Create a non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy only the built jar from the build stage
COPY --from=build /app/target/*.jar app.jar

# Switch to non-root user
USER appuser

EXPOSE 8080

# Use exec form so the app receives signals correctly
ENTRYPOINT ["java", "-jar", "app.jar"]