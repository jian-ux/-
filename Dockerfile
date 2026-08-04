FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    MAVEN_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
COPY feisheng-bot-parent .
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -pl feisheng-bot-admin -am

FROM build AS test
RUN --mount=type=cache,target=/root/.m2 mvn test

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       curl ffmpeg tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
COPY --from=build /app/feisheng-bot-admin/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
HEALTHCHECK --interval=30s --timeout=3s CMD curl -f http://localhost:8080/api/health || exit 1
