FROM openjdk:8-stretch
RUN apt-get update && apt-get install -y apt-transport-https libpq5 ca-certificates && \
    sed -i 's/mozilla\/DST_Root_CA_X3.crt/!mozilla\/DST_Root_CA_X3.crt/' /etc/ca-certificates.conf && \
    update-ca-certificates && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt
RUN adduser --disabled-password --gecos '' builduser && su builduser
USER builduser
COPY --chown=builduser:builduser ./docker /src/docker
COPY --chown=builduser:builduser ./project/build.properties /src/project/
COPY --chown=builduser:builduser ./project/plugins.sbt /src/project/
COPY --chown=builduser:builduser ./project/ScalacOptions.scala /src/project/
COPY --chown=builduser:builduser ./project/Assembly.scala /src/project/
COPY --chown=builduser:builduser ./project/BuildInfo.scala /src/project/
COPY --chown=builduser:builduser ./project/Commands.scala /src/project/
COPY --chown=builduser:builduser ./project/Dependencies.scala /src/project/
COPY --chown=builduser:builduser ./project/versioning.scala /src/project/
COPY --chown=builduser:builduser ./conseil-common/src /src/conseil-common/src
COPY --chown=builduser:builduser ./conseil-api/src /src/conseil-api/src
COPY --chown=builduser:builduser ./conseil-lorre/src /src/conseil-lorre/src
COPY --chown=builduser:builduser ./build.sbt /src
COPY --chown=builduser:builduser ./publishing.sbt /src
WORKDIR /src
RUN sbt clean assembly -J-Xss32m

FROM openjdk:13-alpine
RUN apk add --upgrade apk-tools busybox musl-utils
RUN apk --no-cache add ca-certificates
RUN apk add netcat-openbsd
WORKDIR /root/
COPY --from=0 /tmp/conseil-api.jar conseil-api.jar
COPY --from=0 /tmp/conseil-lorre.jar conseil-lorre.jar
ADD ./conseil-api/src/main/resources/metadata/ /root/
ADD ./conseil-api/src/main/resources/metadata.conf /root/metadata.conf
ADD ./docker/entrypoint.sh /root/entrypoint.sh
ADD ./docker/wait-for.sh /root/wait-for.sh
ADD ./sql/conseil.sql /root/sql/conseil.sql
ADD ./conseil-api/src/main/resources/metadata/tezos.delphinet.conf /root/tezos.delphinet.conf

RUN chmod +x /root/entrypoint.sh
RUN chmod +rx /root/wait-for.sh

ENTRYPOINT ["/root/entrypoint.sh"]
