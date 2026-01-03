# ---------- Build stage ----------
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom.xml and download dependencies first (cache-friendly)
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy source code
COPY src ./src

# Build the fat JAR
RUN mvn clean package -DskipTests


# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the built jar from build stage
COPY --from=build /app/target/hotel-backend-1.0.0.jar app.jar

# Render injects PORT automatically
EXPOSE 8080

# Start the application
CMD ["java", "-jar", "app.jar"]
