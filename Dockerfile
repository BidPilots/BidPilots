# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Download dependencies first (layer-cached unless pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build the JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# ─── Stage 2: Run ─────────────────────────────────────────────────────────────
# Use Debian-based JRE (NOT Alpine) — Chrome/Chromium requires glibc, not musl.
# Alpine's musl libc causes the exact "Exec failed, error: 2" crash you saw.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install Chromium + ChromeDriver from Ubuntu's package manager (glibc — works)
RUN apt-get update && apt-get install -y --no-install-recommends \
        chromium-browser \
        chromium-chromedriver \
        fonts-liberation \
        libasound2 \
        libatk-bridge2.0-0 \
        libatk1.0-0 \
        libcairo2 \
        libcups2 \
        libdbus-1-3 \
        libdrm2 \
        libexpat1 \
        libgbm1 \
        libglib2.0-0 \
        libgtk-3-0 \
        libnspr4 \
        libnss3 \
        libpango-1.0-0 \
        libpangocairo-1.0-0 \
        libx11-6 \
        libx11-xcb1 \
        libxcb1 \
        libxcomposite1 \
        libxcursor1 \
        libxdamage1 \
        libxext6 \
        libxfixes3 \
        libxi6 \
        libxrandr2 \
        libxrender1 \
        libxss1 \
        libxtst6 \
        wget \
        xdg-utils \
    && rm -rf /var/lib/apt/lists/*

# Symlink so WebDriverManager / hardcoded paths both resolve
RUN ln -sf /usr/bin/chromium-browser /usr/bin/chromium \
 && ln -sf /usr/bin/chromedriver      /usr/bin/chromedriver

# Chrome environment variables — used by GeMScrapingService
ENV CHROME_BIN=/usr/bin/chromium-browser
ENV CHROME_PATH=/usr/bin/chromium-browser
ENV CHROMEDRIVER_PATH=/usr/bin/chromedriver

# Tell WebDriverManager to skip downloading — use the system binary
ENV WDM_SKIP_DOWNLOAD=true

# Needed for headless Chrome in a sandboxless container
ENV CHROME_FLAGS="--headless=new --no-sandbox --disable-dev-shm-usage --disable-gpu"

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
CMD ["java", \
     "-Djava.awt.headless=true", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-jar", "app.jar"]
