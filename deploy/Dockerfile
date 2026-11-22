# UPC Pocket 多用户 Web 服务（FastAPI）生产镜像
#
# 构建上下文是仓库根（deploy/ 的上一级），由 deploy/docker-compose.yml 指定：
#   docker compose -f deploy/docker-compose.yml build app
# 镜像内只放服务端代码与上游协议模块；PWA 静态文件由 Caddy 从宿主 ../web 只读挂载，
# 不进入镜像。仓库根 .dockerignore 负责排除 server/.venv、__pycache__、.git 等。
FROM python:3.12-slim

# PYTHONPATH 与工作目录按服务契约固定在 /app/server；上游协议模块在 /app/tools/web。
ENV PYTHONPATH=/app/server \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HOME=/tmp

# 固定非 root 用户 uid/gid 10001：
# - deploy/secrets/ 下的 <kid>.pem、data_key、session_secret 属主需设为 10001，权限 600；
# - 容器 rootfs 只读，可写目录只有 compose 挂载的 /tmp tmpfs。
RUN groupadd --system --gid 10001 upcpocket \
    && useradd --system --uid 10001 --gid 10001 \
        --home-dir /tmp --no-create-home --shell /usr/sbin/nologin upcpocket

WORKDIR /app

# Only runtime files; tests, caches, local venv and build output never ship.
COPY server/pyproject.toml /app/server/
COPY server/app/ /app/server/app/
COPY server/migrations/ /app/server/migrations/
COPY server/scripts/ /app/server/scripts/
COPY tools/web/ /app/tools/web/

# 依赖只来自 server/pyproject.toml；不安装与运行无关的系统包或 Python 包。
RUN pip install --no-cache-dir ./server

WORKDIR /app/server
USER 10001:10001

EXPOSE 8080

# exec form 不能展开变量：用 sh -c 展开 WEB_CONCURRENCY，再用 exec 让 uvicorn 成为 PID 1，
# 从而正确接收 SIGTERM 并优雅退出。
# --no-access-log：访问日志由 Caddy 输出 JSON 并去掉 query；应用自身按 LOG_LEVEL 记录固定字段。
CMD ["sh", "-c", "exec uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8080 --workers \"${WEB_CONCURRENCY:-2}\" --no-access-log --proxy-headers"]
