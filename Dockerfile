ARG INPUT_SOURCE=downloaded
FROM scratch AS downloaded
ADD --checksum=sha256:1770d95488a383f3b97e5e7a1b30f9fd122200a16f88078caa4823951e9aeac2 https://github.com/portfolio-performance/portfolio/releases/download/0.88.0/PortfolioPerformance-0.88.0-linux.gtk.x86_64.tar.gz /release.tar.gz
ADD --checksum=sha256:ce6d10fe45ce5484f8c4ee9ae36eb1654890b03f6adb2fbbd3f54077d93cc656 https://api.github.com/repos/portfolio-performance/portfolio/tarball/8de1fe41d71a2c1b74a6bfbe913f885899aaf123 /source.tar.gz
FROM scratch AS supplied
COPY runtime/build-inputs/upstream-source.tar.gz /source.tar.gz
COPY runtime/build-inputs/upstream-release.tar.gz /release.tar.gz
FROM ${INPUT_SOURCE} AS inputs
FROM eclipse-temurin:21.0.11_10-jdk-jammy@sha256:dbfd085220ae632a0830166e443747d1ee89e9038d92e3b48c3e5e9d8292b9a7 AS build
ARG UPSTREAM_SOURCE_SHA256=ce6d10fe45ce5484f8c4ee9ae36eb1654890b03f6adb2fbbd3f54077d93cc656
ARG UPSTREAM_RELEASE_SHA256=1770d95488a383f3b97e5e7a1b30f9fd122200a16f88078caa4823951e9aeac2
COPY --from=inputs /source.tar.gz /tmp/source.tar.gz
COPY --from=inputs /release.tar.gz /tmp/release.tar.gz
RUN echo "$UPSTREAM_SOURCE_SHA256  /tmp/source.tar.gz" | sha256sum -c - && echo "$UPSTREAM_RELEASE_SHA256  /tmp/release.tar.gz" | sha256sum -c -
RUN mkdir -p /source /release /opt/pp/classes && tar -xzf /tmp/source.tar.gz --strip-components=1 -C /source && tar -xzf /tmp/release.tar.gz -C /release
COPY runtime /wrapper/runtime
RUN java /wrapper/runtime/patches/HeadlessPatch.java /source
RUN find /source/name.abuchen.portfolio/src /source/name.abuchen.portfolio/protos /source/name.abuchen.portfolio.pdfbox3/src /wrapper/runtime/src/main/java -name '*.java' > /tmp/sources && javac -encoding UTF-8 --release 21 -cp '/release/portfolio/plugins/*' -d /opt/pp/classes @/tmp/sources
RUN cd /source/name.abuchen.portfolio/src && find . -type f ! -name '*.java' -exec cp --parents '{}' /opt/pp/classes/ \; && mkdir /opt/pp/lib && cp /release/portfolio/plugins/*.jar /opt/pp/lib/ && rm /opt/pp/lib/name.abuchen.portfolio_*.jar /opt/pp/lib/name.abuchen.portfolio.ui_*.jar /opt/pp/lib/name.abuchen.portfolio.pdfbox3_*.jar
RUN mkdir -p /opt/pp/attribution && cp /source/LICENSE /opt/pp/attribution/UPSTREAM-LICENSE && cp /source/name.abuchen.portfolio/META-INF/MANIFEST.MF /opt/pp/attribution/UPSTREAM-MANIFEST.MF && cp /tmp/source.tar.gz /opt/pp/attribution/upstream-source.tar.gz && cp /wrapper/runtime/patches/* /opt/pp/attribution/
RUN cp /wrapper/runtime/help.txt /opt/pp/classes/help.txt
FROM eclipse-temurin:21.0.11_10-jdk-jammy@sha256:dbfd085220ae632a0830166e443747d1ee89e9038d92e3b48c3e5e9d8292b9a7
ARG UPSTREAM_REVISION=8de1fe41d71a2c1b74a6bfbe913f885899aaf123
ARG UPSTREAM_RELEASE=0.88.0
ARG WRAPPER_REVISION=development
LABEL pal.wrapper.revision="$WRAPPER_REVISION"
LABEL org.opencontainers.image.title="Portfolio Performance PAL headless core" org.opencontainers.image.version="$UPSTREAM_RELEASE-model70" pal.upstream.revision="$UPSTREAM_REVISION" pal.xml.model="70"
COPY --from=build /opt/pp /opt/pp
ENV LANG=C.UTF-8
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-cp", "/opt/pp/classes:/opt/pp/lib/*", "pal.Main"]
