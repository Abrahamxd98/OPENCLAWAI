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

ARG APK_KEYSTORE_B64
ARG APK_KEYSTORE_PASSWORD
ARG APK_KEY_ALIAS
ARG APK_KEY_PASSWORD

WORKDIR /src
COPY wa-app/ /src/
RUN test -n "$APK_KEYSTORE_B64" && \
    test -n "$APK_KEYSTORE_PASSWORD" && \
    test -n "$APK_KEY_ALIAS" && \
    test -n "$APK_KEY_PASSWORD" && \
    printf '%s' "$APK_KEYSTORE_B64" | base64 -d > /tmp/grabadorwa.p12 && \
    gradle --no-daemon assembleRelease && \
    zipalign -f -p 4 /src/app/build/outputs/apk/release/app-release-unsigned.apk /src/app-release-aligned.apk && \
    apksigner sign \
      --ks /tmp/grabadorwa.p12 \
      --ks-type PKCS12 \
      --ks-key-alias "$APK_KEY_ALIAS" \
      --ks-pass pass:"$APK_KEYSTORE_PASSWORD" \
      --key-pass pass:"$APK_KEY_PASSWORD" \
      --out /src/Grabador-Llamadas-v0.3.0.apk \
      /src/app-release-aligned.apk && \
    apksigner verify --verbose --print-certs /src/Grabador-Llamadas-v0.3.0.apk && \
    aapt dump badging /src/Grabador-Llamadas-v0.3.0.apk > /tmp/badging.txt 2>&1 && \
    cat /tmp/badging.txt && \
    ! grep -q "error:" /tmp/badging.txt

FROM python:3.12-slim
WORKDIR /srv
COPY --from=build /src/Grabador-Llamadas-v0.3.0.apk /srv/Grabador-Llamadas-v0.3.0.apk
RUN cp /srv/Grabador-Llamadas-v0.3.0.apk /srv/Grabador-WA.apk && \
    cp /srv/Grabador-Llamadas-v0.3.0.apk /srv/Grabador-Llamadas.apk
CMD ["sh","-c","python -m http.server ${PORT:-8080} --bind 0.0.0.0"]
