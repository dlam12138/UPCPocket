#!/usr/bin/env bash
# UPC Pocket · PostgreSQL 加密备份
#
# 在宿主机仓库根执行（需要 docker compose 可用、db 服务已运行、已安装 age）：
#   ./deploy/backup.sh
#
# 行为：
# - 通过 `docker compose exec -T db pg_dump` 在容器内做 plain SQL 导出（容器内本地 socket 认证），
#   流式交给宿主机 age 用 AGE_RECIPIENT（age 公钥）加密，不落明文 SQL，也不打印 DSN/密码。
# - 输出 deploy/backup/upcpocket-<UTC 时间>.sql.age（可用 BACKUP_DIR 覆盖）。
# - 默认保留 30 天；可用 RETENTION_DAYS 覆盖。
# - 任一步失败返回非零，且删除写到一半的临时文件。
#
# 环境变量：
#   AGE_RECIPIENT    age 公钥（默认从 deploy/.env 读取）
#   BACKUP_DIR       备份目录（默认 deploy/backup）
#   RETENTION_DAYS   保留天数（默认 30）
#   ENV_FILE         compose env 文件（默认 deploy/.env）
#   DB_NAME/DB_USER  默认 upcpocket/upcpocket
#
# 定时执行示例（见 deploy/README.md）：
#   15 3 * * * cd /srv/upcpocket && ./deploy/backup.sh >> /var/log/upcpocket-backup.log 2>&1
#
# 恢复演练见 deploy/restore.sh 与 README 的「备份与恢复」。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$SCRIPT_DIR/docker-compose.yml}"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/.env}"
BACKUP_DIR="${BACKUP_DIR:-$SCRIPT_DIR/backup}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
DB_SERVICE="${DB_SERVICE:-db}"
DB_NAME="${DB_NAME:-upcpocket}"
DB_USER="${DB_USER:-upcpocket}"

die() {
	printf '备份失败：%s\n' "$*" >&2
	exit 1
}

command -v docker >/dev/null 2>&1 || die "未找到 docker"
command -v age >/dev/null 2>&1 || die "未找到 age（Debian/Ubuntu: apt install age；macOS: brew install age）"
[ -r "$COMPOSE_FILE" ] || die "找不到 compose 文件：$COMPOSE_FILE"

compose=(docker compose -f "$COMPOSE_FILE")
if [ -r "$ENV_FILE" ]; then
	compose+=(--env-file "$ENV_FILE")
fi

# 只读取 AGE_RECIPIENT 单个键，不 source .env，避免密码进入环境或被意外打印。
AGE_RECIPIENT="${AGE_RECIPIENT:-}"
if [ -z "$AGE_RECIPIENT" ] && [ -r "$ENV_FILE" ]; then
	AGE_RECIPIENT="$(sed -n 's/^[[:space:]]*AGE_RECIPIENT[[:space:]]*=[[:space:]]*//p' "$ENV_FILE" |
		tail -n 1 | tr -d '\r' | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
fi
[ -n "$AGE_RECIPIENT" ] || die "AGE_RECIPIENT 未设置（写入 $ENV_FILE 或导出环境变量）"
case "$AGE_RECIPIENT" in
age1*) : ;;
*) die "AGE_RECIPIENT 不是 age 公钥（应以 age1 开头）" ;;
esac

# 确认 db 服务在运行，避免在容器未启动时产生误导性错误。
if ! "${compose[@]}" ps --status running --services 2>/dev/null | grep -qx "$DB_SERVICE"; then
	die "db 服务未运行：先执行 docker compose -f \"$COMPOSE_FILE\" --env-file \"$ENV_FILE\" up -d"
fi

umask 077
mkdir -p -- "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$BACKUP_DIR/upcpocket-$timestamp.sql.age"
tmp="$out.partial"
trap 'rm -f -- "$tmp"' EXIT

# pg_dump 在 db 容器内以本地 socket 运行，不需要也不会输出数据库密码。
"${compose[@]}" exec -T "$DB_SERVICE" \
	pg_dump -U "$DB_USER" --no-owner --no-privileges -d "$DB_NAME" |
	age -e -r "$AGE_RECIPIENT" -o "$tmp"

[ -s "$tmp" ] || die "pg_dump 输出为空"
# age 二进制格式固定以 "age-encryption.org/v1" 开头；确认确实加密后再落盘命名。
grep -aq '^age-encryption.org/v1' "$tmp" ||
	die "加密输出缺少 age 文件头"

mv -f -- "$tmp" "$out"
trap - EXIT

# 仅清理本脚本命名的历史备份，保留其它文件；-mtime +N 表示超过 N 天。
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'upcpocket-*.sql.age' -mtime "+$RETENTION_DAYS" -print -delete

size="$(wc -c <"$out" | tr -d '[:space:]')"
printf '备份完成：%s（%s 字节，保留 %s 天）\n' "$out" "$size" "$RETENTION_DAYS"
