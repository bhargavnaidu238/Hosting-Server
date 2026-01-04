# ---------- Build stage ----------
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src
RUN mvn clean package -DskipTests


# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /app/target/hotel-backend-1.0.0.jar app.jar

# Render provides PORT
CMD ["java", "-jar", "app.jar"]
