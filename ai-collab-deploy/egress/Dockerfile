# egress-proxy：ai-worker 唯一的公网出口（tinyproxy，域名白名单 + 只放行 CONNECT 443）。
# 基础镜像与 tinyproxy 版本都固定；升级时同步修改 compose 里的 image tag。
FROM alpine:3.21@sha256:bc21487101ae47172509130ee1092cc273a831968d64b617776d1f69c81e42ab

RUN apk add --no-cache tinyproxy=1.11.2-r0

COPY --chmod=0555 entrypoint.sh /usr/local/bin/egress-entrypoint

# 非 root 运行；监听端口 8888 > 1024，不需要任何 capability。
USER tinyproxy
EXPOSE 8888
ENTRYPOINT ["/usr/local/bin/egress-entrypoint"]
