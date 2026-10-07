FROM eclipse-temurin:21.0.11_10-jdk-jammy@sha256:dbfd085220ae632a0830166e443747d1ee89e9038d92e3b48c3e5e9d8292b9a7 AS build
ADD --checksum=sha256:1770d95488a383f3b97e5e7a1b30f9fd122200a16f88078caa4823951e9aeac2 https://github.com/portfolio-performance/portfolio/releases/download/0.88.0/PortfolioPerformance-0.88.0-linux.gtk.x86_64.tar.gz /tmp/release.tar.gz
ADD --checksum=sha256:ce6d10fe45ce5484f8c4ee9ae36eb1654890b03f6adb2fbbd3f54077d93cc656 https://api.github.com/repos/portfolio-performance/portfolio/tarball/8de1fe41d71a2c1b74a6bfbe913f885899aaf123 /tmp/source.tar.gz
RUN mkdir -p /source /release /opt/pp/classes && tar -xzf /tmp/source.tar.gz --strip-components=1 -C /source && tar -xzf /tmp/release.tar.gz -C /release
COPY runtime /wrapper/runtime
RUN java /wrapper/runtime/patches/HeadlessPatch.java /source
RUN find /source/name.abuchen.portfolio/src /source/name.abuchen.portfolio/protos /source/name.abuchen.portfolio.pdfbox3/src /wrapper/runtime/src/main/java -name '*.java' > /tmp/sources && javac -encoding UTF-8 --release 21 -cp '/release/portfolio/plugins/*' -d /opt/pp/classes @/tmp/sources
RUN cd /source/name.abuchen.portfolio/src && find . -type f ! -name '*.java' -exec cp --parents '{}' /opt/pp/classes/ \; && mkdir /opt/pp/lib && cp /release/portfolio/plugins/*.jar /opt/pp/lib/ && rm /opt/pp/lib/name.abuchen.portfolio_*.jar /opt/pp/lib/name.abuchen.portfolio.ui_*.jar /opt/pp/lib/name.abuchen.portfolio.pdfbox3_*.jar
RUN mkdir -p /opt/pp/attribution && cp /source/LICENSE /opt/pp/attribution/UPSTREAM-LICENSE && cp /source/name.abuchen.portfolio/META-INF/MANIFEST.MF /opt/pp/attribution/UPSTREAM-MANIFEST.MF && cp /tmp/source.tar.gz /opt/pp/attribution/upstream-source.tar.gz && cp /wrapper/runtime/patches/* /opt/pp/attribution/
FROM eclipse-temurin:21.0.11_10-jdk-jammy@sha256:dbfd085220ae632a0830166e443747d1ee89e9038d92e3b48c3e5e9d8292b9a7
LABEL org.opencontainers.image.title="Portfolio Performance PAL headless core" org.opencontainers.image.version="0.88.0-model70" pal.upstream.revision="8de1fe41d71a2c1b74a6bfbe913f885899aaf123" pal.xml.model="70"
COPY --from=build /opt/pp /opt/pp
ENV LANG=C.UTF-8
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-cp", "/opt/pp/classes:/opt/pp/lib/*", "pal.Core"]
