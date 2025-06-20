FROM openjdk:17-jdk-slim

LABEL maintainer="Vulnerable Web App Team"
LABEL description="Intentionally vulnerable Java web application for security testing"

# Create app directory
WORKDIR /app

# Install curl for health checks
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

# Copy Maven dependencies (for better caching)
COPY pom.xml .
COPY src ./src

# Build the application
RUN apt-get update && \
    apt-get install -y maven && \
    mvn clean package -DskipTests && \
    mv target/*.jar app.jar && \
    apt-get remove -y maven && \
    apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf ~/.m2

# Alternatively, if building outside Docker:
# COPY target/*.jar app.jar

# Create non-root user for security (though this is a vulnerable app)
RUN addgroup --system appgroup && \
    adduser --system --ingroup appgroup appuser && \
    chown appuser:appgroup /app/app.jar

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

# JVM options for production
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC"
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"] 