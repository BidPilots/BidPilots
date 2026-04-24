# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install Chrome for Selenium
RUN apk add --no-cache chromium chromium-chromedriver

# Copy the JAR from build stage
COPY --from=build /app/target/*.jar app.jar

# Chrome environment variables
ENV CHROME_BIN=/usr/bin/chromium-browser
ENV CHROME_PATH=/usr/bin/chromium-browser

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
