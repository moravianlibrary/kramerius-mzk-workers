ARG BASE_IMAGE=ceskaexpedice/curator-worker:7.2.2.4

FROM ${BASE_IMAGE} AS base

FROM gradle:8.10.2-jdk21 AS build
COPY --from=base /usr/local/tomcat/webapps/worker.war /tmp/worker.war
RUN mkdir /base && cd /base && jar xf /tmp/worker.war WEB-INF/lib WEB-INF/classes
WORKDIR /src
COPY settings.gradle build.gradle ./
COPY plugins plugins
RUN gradle --no-daemon build buildProcessPlugin \
    && mkdir /dist \
    && cp -r plugins/*/build/distributions/* /dist/

FROM base
COPY --from=build /dist/ /usr/local/tomcat/lib/plugins/
