FROM python:3.13-slim@sha256:d98cc29d3266e535dd9da1099688d9cb3ca4753813571e071763d17725460962
WORKDIR /service
COPY --chmod=0444 gateway/server.py /service/gateway/server.py
COPY --chmod=0444 app/src/main/assets/seoul_subway_stations.json /service/app/src/main/assets/seoul_subway_stations.json
RUN chmod 0755 /service/gateway /service/app /service/app/src /service/app/src/main /service/app/src/main/assets \
    && mkdir /data && chown 65532:65532 /data
USER 65532:65532
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 SUBWAY_GATEWAY_DB=/data/requests.sqlite3
EXPOSE 8787
CMD ["python", "gateway/server.py", "--bind", "0.0.0.0", "--port", "8787"]
