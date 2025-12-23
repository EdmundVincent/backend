#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../compose/docker-compose.dev.yml"
DC_CMD=(docker compose -f "${COMPOSE_FILE}")

MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin}"
BROKER="${BROKER:-redpanda:9092}"

log() { echo "[init_all] $*"; }

compose_cp() {
  "${DC_CMD[@]}" cp "$@"
}

log "Ensuring required services are running (without rag-worker)..."
"${DC_CMD[@]}" up -d redpanda postgres minio mc kafka-tools qdrant >/dev/null

wait_for_postgres() {
  log "Waiting for Postgres..."
  local attempts=0
  until "${DC_CMD[@]}" exec -T postgres pg_isready -U rag_user -d rag_db >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if ((attempts > 30)); then
      log "Postgres is not ready after multiple attempts."
      exit 1
    fi
    sleep 2
  done
  log "Postgres is ready."
}

apply_db_schema() {
  log "Applying init_db.sql..."
  compose_cp "${SCRIPT_DIR}/init_db.sql" postgres:/tmp/init_db.sql
  "${DC_CMD[@]}" exec -T postgres psql -U rag_user -d rag_db -v ON_ERROR_STOP=1 -f /tmp/init_db.sql
  log "Postgres schema applied."
}

wait_for_minio() {
  log "Waiting for MinIO..."
  local attempts=0
  until "${DC_CMD[@]}" exec -T \
    -e MINIO_ROOT_USER="${MINIO_ROOT_USER}" \
    -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
    mc sh -c "mc alias set local http://minio:9000 \"\$MINIO_ROOT_USER\" \"\$MINIO_ROOT_PASSWORD\" >/dev/null 2>&1 && mc ls local >/dev/null 2>&1"; do
    attempts=$((attempts + 1))
    if ((attempts > 30)); then
      log "MinIO is not reachable after multiple attempts."
      exit 1
    fi
    sleep 2
  done
  log "MinIO is reachable."
}

run_minio_init() {
  log "Running init_minio.sh..."
  compose_cp "${SCRIPT_DIR}/init_minio.sh" mc:/tmp/init_minio.sh
  "${DC_CMD[@]}" exec -T \
    -e MINIO_ROOT_USER="${MINIO_ROOT_USER}" \
    -e MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD}" \
    mc sh /tmp/init_minio.sh
  log "MinIO initialization finished."
}

ensure_rpk_in_kafka_tools() {
  if "${DC_CMD[@]}" exec -T kafka-tools sh -c "command -v rpk >/dev/null 2>&1"; then
    return
  fi

  log "Copying rpk binary from redpanda into kafka-tools..."
  local redpanda_c
  local kafka_tools_c
  redpanda_c="$("${DC_CMD[@]}" ps -q redpanda)"
  kafka_tools_c="$("${DC_CMD[@]}" ps -q kafka-tools)"
  if [ -z "${redpanda_c}" ] || [ -z "${kafka_tools_c}" ]; then
    log "Unable to resolve container IDs for redpanda or kafka-tools."
    exit 1
  fi

  tmp_rpk="$(mktemp)"
  docker cp "${redpanda_c}:/opt/redpanda/libexec/rpk" "${tmp_rpk}"
  docker cp "${tmp_rpk}" "${kafka_tools_c}:/usr/bin/rpk"
  rm -f "${tmp_rpk}"
  "${DC_CMD[@]}" exec -T kafka-tools sh -c "chmod +x /usr/bin/rpk"
}

wait_for_kafka() {
  log "Waiting for Redpanda..."
  local attempts=0
  until "${DC_CMD[@]}" exec -T kafka-tools sh -c "rpk --brokers ${BROKER} cluster info >/dev/null 2>&1"; do
    attempts=$((attempts + 1))
    if ((attempts > 30)); then
      log "Redpanda is not reachable after multiple attempts."
      exit 1
    fi
    sleep 2
  done
  log "Redpanda is reachable."
}

run_kafka_init() {
  log "Running init_kafka.sh..."
  compose_cp "${SCRIPT_DIR}/init_kafka.sh" kafka-tools:/tmp/init_kafka.sh
  "${DC_CMD[@]}" exec -T -e BROKER="${BROKER}" kafka-tools sh /tmp/init_kafka.sh
  log "Kafka initialization finished."
}

optional_qdrant_check() {
  log "Checking Qdrant health (optional)..."
  if "${DC_CMD[@]}" exec -T qdrant bash -c "echo > /dev/tcp/localhost/6333" >/dev/null 2>&1; then
    log "Qdrant is reachable."
  else
    log "Qdrant health check skipped/failed (non-blocking)."
  fi
}

wait_for_postgres
apply_db_schema

wait_for_minio
run_minio_init

ensure_rpk_in_kafka_tools
wait_for_kafka
run_kafka_init

optional_qdrant_check

log "All initialization steps completed."
