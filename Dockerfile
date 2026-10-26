FROM gradle:8.11.1-jdk17 AS build
USER root
RUN apt-get update && apt-get install -y --no-install-recommends wget unzip ca-certificates && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/36.0.0
RUN mkdir -p /opt/android-sdk/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -O /tmp/cmdline.zip && \
    unzip -q /tmp/cmdline.zip -d /tmp/cmdline && \
    mkdir -p /opt/android-sdk/cmdline-tools/latest && \
    mv /tmp/cmdline/cmdline-tools/* /opt/android-sdk/cmdline-tools/latest/ && \
    rm -rf /tmp/cmdline /tmp/cmdline.zip && \
    yes | sdkmanager --licenses >/dev/null || true
RUN sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
WORKDIR /src
COPY wa-app/ /src/
RUN gradle --no-daemon assembleDebug && \
    apksigner verify --verbose --print-certs /src/app/build/outputs/apk/debug/app-debug.apk && \
    aapt dump badging /src/app/build/outputs/apk/debug/app-debug.apk > /tmp/badging.txt 2>&1 && \
    cat /tmp/badging.txt && \
    ! grep -q "error:" /tmp/badging.txt

FROM python:3.12-slim
WORKDIR /srv
COPY --from=build /src/app/build/outputs/apk/debug/app-debug.apk /srv/Grabador-WA-v0.1.3.apk
RUN cp /srv/Grabador-WA-v0.1.3.apk /srv/Grabador-WA.apk && \
    cp /srv/Grabador-WA-v0.1.3.apk /srv/Grabador-WA-v0.1.apk && \
    cp /srv/Grabador-WA-v0.1.3.apk /srv/Grabador-WA-v0.1.2.apk
CMD ["sh","-c","python -m http.server ${PORT:-8080} --bind 0.0.0.0"]
