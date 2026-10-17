FROM python:3.13-alpine
ARG SOURCE_REVISION=unversioned
ARG BUILD_ID=local
LABEL org.opencontainers.image.revision=$SOURCE_REVISION io.infrastructure.build-id=$BUILD_ID
WORKDIR /app
COPY infra/alertmanager/receiver.py /app/receiver.py
COPY infra/alertmanager/email_settings.py /app/email_settings.py
RUN mkdir -p /var/lib/alert-email && chown 65534:65534 /var/lib/alert-email && chmod 700 /var/lib/alert-email
USER 65534:65534
EXPOSE 8090
CMD ["python", "-B", "/app/receiver.py"]
