ARG BASE_IMAGE=ceskaexpedice/curator-worker:7.2.2.5

FROM ${BASE_IMAGE} AS base

FROM gradle:8.10.2-jdk21 AS build
COPY --from=base /usr/local/tomcat/webapps/worker.war /tmp/worker.war
RUN mkdir /base && cd /base && jar xf /tmp/worker.war WEB-INF/lib WEB-INF/classes
WORKDIR /src
COPY settings.gradle build.gradle ./
COPY plugins plugins
# Comma-separated plugin directory names; empty builds and ships every plugin under plugins/.
ARG PLUGINS=""
RUN set -e; \
    selected=$(echo "${PLUGINS}" | tr ',' ' '); \
    if [ -z "$selected" ]; then selected=$(ls plugins); fi; \
    tasks=""; \
    for plugin in $selected; do \
        if [ ! -f "plugins/$plugin/build.gradle" ]; then \
            echo "Unknown plugin '$plugin'; available: $(ls plugins | tr '\n' ' ')"; exit 1; \
        fi; \
        tasks="$tasks :plugins:$plugin:build :plugins:$plugin:buildProcessPlugin"; \
    done; \
    gradle --no-daemon $tasks; \
    mkdir /dist; \
    for plugin in $selected; do cp -r "plugins/$plugin/build/distributions/"* /dist/; done

FROM base
COPY --from=build /dist/ /usr/local/tomcat/lib/plugins/
