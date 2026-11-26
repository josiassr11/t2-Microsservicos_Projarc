# monitor-ieducar (Spring Boot). Build multi-stage: não precisa de Maven/JDK na máquina.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/monitor-ieducar-*.jar app.jar
USER app
EXPOSE 8080
# liveness: o container só fica "unhealthy" se o próprio processo travar — RabbitMQ/Postgres fora do ar NÃO derrubam o
# serviço (ele continua vivo, tentando de novo). O estado das dependências aparece em /actuator/health.
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/liveness || exit 1
# UTF-8 explícito: no Java 17 o encoding do console depende do locale do container (sem isso os acentos viram "?").
ENV LANG=C.UTF-8
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-jar", "/app/app.jar"]
