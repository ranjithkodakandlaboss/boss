FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S boss && adduser -S boss -G boss
USER boss
COPY target/boss-admin-api-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
