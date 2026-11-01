# syntax=docker/dockerfile:1
# 前端生产镜像：多阶段构建（容器内完成 pnpm build，宿主机无需 Node/pnpm），
# 运行阶段为 Nginx + 打包后的静态资源。构建上下文固定为仓库根目录。
# 镜像默认站点配置为 local.conf（HTTP 80，同源代理 /api 与 /storage），
# 独立运行即可提供静态资源；compose.local.yml 直接使用该默认，
# compose.prod.yml 显式挂载 production.conf（TLS）覆盖。
# ---- 构建阶段 ----
FROM node:22-alpine@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914 AS build
WORKDIR /build

# 版本与 package.json 的 packageManager（pnpm@11.9.0）精确对齐。
RUN npm install -g pnpm@11.9.0

COPY ai-collab-frontend/package.json ai-collab-frontend/pnpm-lock.yaml ai-collab-frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY ai-collab-frontend/ ./
RUN pnpm build

# ---- 运行阶段 ----
FROM nginx:1.30.6-alpine@sha256:0e52bd054126cdc040b1276c0af347aeb02a41b9cfe305d6094767ac30e97949

COPY ai-collab-deploy/nginx/local.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

# 构建可追溯：revision 由 CI 或本地构建脚本以 --label 注入，不做强制。
EXPOSE 80 443
