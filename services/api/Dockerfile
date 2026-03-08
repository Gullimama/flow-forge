FROM flowforge-base:latest
COPY services/api/build/libs/api-*.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s CMD curl -f http://localhost:8080/actuator/health || exit 1
