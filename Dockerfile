FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 app \
    && mkdir -p /data /models \
    && chown app:app /data /models
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/*.jar /app/app.jar
ENV SERVER_ADDRESS=0.0.0.0 \
    PORT=8080 \
    CLOSET_STORAGE_DIR=/data \
    CLOSET_MODEL_PATH=/models/u2net.onnx \
    CLOSET_EMBEDDING_MODEL_PATH=/models/embedding-fp32.onnx \
    CLOSET_EMBEDDING_VOCAB_PATH=/models/embedding-vocab.txt
VOLUME ["/data", "/models"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/api/health || exit 1
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
