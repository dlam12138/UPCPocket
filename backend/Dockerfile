FROM gradle:8.13-jdk21 AS build

WORKDIR /workspace
COPY --chown=gradle:gradle . .
USER gradle
RUN sh ./gradlew --no-daemon --max-workers=1 bootJar

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --system --gid 10001 pocket \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin pocket
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/upcpocket-backend-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["/bin/sh", "-c", "set -eu; ulimit -c 0; umask 077; mkdir -p /tmp/pocket-secrets; cp -- \"$POCKET_DB_PASSWORD_FILE\" /tmp/pocket-secrets/db-password; cp -- \"$POCKET_VAULT_MASTER_KEYS_FILE\" /tmp/pocket-secrets/master-keys; cp -- \"$POCKET_VAULT_LOGIN_INDEX_KEY_FILE\" /tmp/pocket-secrets/login-index-key; cp -- \"$POCKET_VAULT_AUDIT_INDEX_KEY_FILE\" /tmp/pocket-secrets/audit-index-key; chmod 400 /tmp/pocket-secrets/*; POCKET_DB_PASSWORD=\"$(cat /tmp/pocket-secrets/db-password)\"; export POCKET_DB_PASSWORD; POCKET_VAULT_MASTER_KEYS_FILE=/tmp/pocket-secrets/master-keys; POCKET_VAULT_LOGIN_INDEX_KEY_FILE=/tmp/pocket-secrets/login-index-key; POCKET_VAULT_AUDIT_INDEX_KEY_FILE=/tmp/pocket-secrets/audit-index-key; export POCKET_VAULT_MASTER_KEYS_FILE POCKET_VAULT_LOGIN_INDEX_KEY_FILE POCKET_VAULT_AUDIT_INDEX_KEY_FILE; exec java -XX:+ExitOnOutOfMemoryError -XX:-HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dev/null -Djava.io.tmpdir=/tmp -jar /app/app.jar"]
