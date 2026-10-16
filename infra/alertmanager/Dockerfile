FROM python:3.13-alpine
ARG SOURCE_REVISION=unversioned
ARG BUILD_ID=local
LABEL org.opencontainers.image.revision=$SOURCE_REVISION io.infrastructure.build-id=$BUILD_ID
WORKDIR /app
COPY infra/alertmanager/receiver.py /app/receiver.py
USER 65534:65534
EXPOSE 8090
CMD ["python", "-B", "/app/receiver.py"]
