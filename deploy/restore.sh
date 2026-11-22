#!/usr/bin/env bash
# UPC Pocket · 从 age 加密备份恢复 PostgreSQL
#
# 危险操作：会停止 app、删除并重建 upcpocket 数据库，再从备份导入。
# 必须显式传入 --yes，且在终端交互时再输入 restore 二次确认。
#
# 用法：
#   sudo ./deploy/restore.sh deploy/backup/upcpocket-20261122T031500Z.sql.age --yes
#
# 前置：
# - age 私钥默认 ~/.config/upc-pocket/age.key（用 AGE_IDENTITY_FILE 覆盖）。
#   私钥必须离线保管，不要放进 deploy/secrets/（该目录会挂给服务容器）。
# - db 服务已运行；app 会被停止并在恢复后重新启动。
#
# 失败时数据库可能处于部分导入状态；修复后重新执行本脚本会再次从干净库重放。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$SCRIPT_DIR/docker-compose.yml}"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/.env}"
AGE_IDENTITY_FILE="${AGE_IDENTITY_FILE:-$HOME/.config/upc-pocket/age.key}"
DB_SERVICE="${DB_SERVICE:-db}"
DB_NAME="${DB_NAME:-upcpocket}"
DB_USER="${DB_USER:-upcpocket}"

usage() {
	cat >&2 <<'EOF'
用法：restore.sh <backup.sql.age> --yes

  <backup.sql.age>   由 deploy/backup.sh 生成的 age 加密备份
  --yes              显式确认：会删除并重建 upcpocket 数据库
  环境变量：AGE_IDENTITY_FILE（默认 ~/.config/upc-pocket/age.key）、ENV_FILE、DB_NAME、DB_USER
EOF
}

die() {
	printf '恢复失败：%s\n' "$*" >&2
	exit 1
}

[ "$#" -ge 2 ] || {
	usage
	exit 2
}
BACKUP_FILE="$1"
case "$2" in
--yes) : ;;
*)
	usage
	die "缺少显式确认参数 --yes"
	;;
esac

[ -r "$BACKUP_FILE" ] || die "备份文件不可读：$BACKUP_FILE"
[ -r "$AGE_IDENTITY_FILE" ] || die "age 私钥不可读：$AGE_IDENTITY_FILE"
command -v docker >/dev/null 2>&1 || die "未找到 docker"
command -v age >/dev/null 2>&1 || die "未找到 age"
[ -r "$COMPOSE_FILE" ] || die "找不到 compose 文件：$COMPOSE_FILE"

case "$DB_NAME" in
postgres | template0 | template1) die "拒绝操作系统数据库：$DB_NAME" ;;
esac
case "$DB_NAME" in
*[!A-Za-z0-9_]* | [0-9]*) die "DB_NAME 只允许字母、数字、下划线且不能以数字开头" ;;
esac
grep -q '^AGE-SECRET-KEY-1' "$AGE_IDENTITY_FILE" || die "age 私钥文件格式异常（应含 AGE-SECRET-KEY-1 行）"
grep -aq '^age-encryption.org/v1' "$BACKUP_FILE" || die "备份文件不是 age 加密格式"

compose=(docker compose -f "$COMPOSE_FILE")
if [ -r "$ENV_FILE" ]; then
	compose+=(--env-file "$ENV_FILE")
fi

if ! "${compose[@]}" ps --status running --services 2>/dev/null | grep -qx "$DB_SERVICE"; then
	die "db 服务未运行：先执行 docker compose -f \"$COMPOSE_FILE\" --env-file \"$ENV_FILE\" up -d db"
fi

# 终端交互时要求再输入一次 restore，避免复制粘贴误执行。
if [ -t 0 ]; then
	printf '即将停止 app 并删除/重建数据库 "%s"，输入 restore 继续：' "$DB_NAME" >&2
	read -r answer
	[ "$answer" = "restore" ] || die "未确认，已取消"
fi

printf '1/4 停止 app（避免恢复期间写入）\n' >&2
"${compose[@]}" stop app >/dev/null || die "无法停止 app；为避免恢复期间写入，已中止"
if "${compose[@]}" ps --status running --services 2>/dev/null | grep -qx app; then
	die "app 仍在运行；为避免恢复期间写入，已中止"
fi

printf '2/4 终止连接并重建数据库 "%s"\n' "$DB_NAME" >&2
"${compose[@]}" exec -T "$DB_SERVICE" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres \
	-c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid();" \
	-c "DROP DATABASE IF EXISTS \"$DB_NAME\";" \
	-c "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_USER\";" >/dev/null

printf '3/4 解密并导入备份（流式，不落明文 SQL）\n' >&2
# age 解密走管道，明文 SQL 不写磁盘；psql 遇错即停。
age -d -i "$AGE_IDENTITY_FILE" "$BACKUP_FILE" |
	"${compose[@]}" exec -T "$DB_SERVICE" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" >/dev/null

printf '4/4 启动 app 并等待健康检查\n' >&2
"${compose[@]}" up -d app >/dev/null

printf '恢复流程结束。请验证：\n' >&2
printf '  docker compose -f "%s" exec -T app python -c "import urllib.request; print(urllib.request.urlopen(\\"http://127.0.0.1:8080/healthz\\", timeout=3).status)"\n' "$COMPOSE_FILE" >&2
printf '  以及浏览器访问站点首页与预约只读查询，确认数据完整。\n' >&2
