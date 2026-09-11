FROM eclipse-temurin:26-jdk AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY pom.xml ./
COPY contracts contracts
COPY services services
ARG SERVICE
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl services/${SERVICE}-service -am package

FROM eclipse-temurin:26-jdk AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ARG SERVICE
COPY --from=build /workspace/services/${SERVICE}-service/target/${SERVICE}-service-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java","-jar","/app/app.jar"]
