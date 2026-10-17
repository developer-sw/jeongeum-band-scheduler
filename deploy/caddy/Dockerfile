FROM caddy:2-alpine@sha256:d8542f48d34a9cf4e4c11a478865229840e87e4c96ea3f439101f31a5d35f75f
RUN mkdir -p /data /config && chown -R 10001:10001 /data /config
USER 10001:10001
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=6 \
  CMD wget -q --spider http://127.0.0.1:2019/config/ || exit 1
