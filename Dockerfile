FROM clojure:temurin-21-tools-deps-alpine AS jre-build

ENV TAILWIND_VERSION=v3.4.17

RUN apk add curl rlwrap && curl -L -o /usr/local/bin/tailwindcss \
  https://github.com/tailwindlabs/tailwindcss/releases/download/$TAILWIND_VERSION/tailwindcss-linux-x64 \
  && chmod +x /usr/local/bin/tailwindcss

WORKDIR /app
COPY src ./src
COPY dev ./dev
COPY resources ./resources
COPY deps.edn .

RUN clj -M:dev uberjar && cp target/jar/app.jar . && rm -r target

FROM eclipse-temurin:21-alpine
WORKDIR /app

COPY --from=jre-build /app/app.jar /app/app.jar

EXPOSE 8080

ENV BIFF_PROFILE=prod
ENV HOST=0.0.0.0
ENV PORT=8080
CMD ["/opt/java/openjdk/bin/java", "-XX:-OmitStackTraceInFastThrow", "-XX:+CrashOnOutOfMemoryError", "-jar", "app.jar"]
