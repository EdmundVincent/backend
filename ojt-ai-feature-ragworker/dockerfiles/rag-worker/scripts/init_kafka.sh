#!/bin/sh
set -e

BROKER=${BROKER:-redpanda:9092}
RPK_CMD="rpk --brokers ${BROKER}"
DEFAULT_PARTITIONS=${DEFAULT_PARTITIONS:-3}
DEFAULT_REPLICATION=${DEFAULT_REPLICATION:-1}
DOC_INGEST_PARTITIONS=${DOC_INGEST_PARTITIONS:-3}
DOC_INGEST_REPLICATION=${DOC_INGEST_REPLICATION:-1}
DOC_INDEXED_PARTITIONS=${DOC_INDEXED_PARTITIONS:-1}
DOC_INDEXED_REPLICATION=${DOC_INDEXED_REPLICATION:-1}
DOC_FAILED_PARTITIONS=${DOC_FAILED_PARTITIONS:-1}
DOC_FAILED_REPLICATION=${DOC_FAILED_REPLICATION:-1}
RAG_PARTITIONS=${RAG_PARTITIONS:-${DEFAULT_PARTITIONS}}
RAG_REPLICATION=${RAG_REPLICATION:-${DEFAULT_REPLICATION}}

echo "[init_kafka] Using broker ${BROKER}"
echo "[init_kafka] Default partitions=${DEFAULT_PARTITIONS}, replication=${DEFAULT_REPLICATION}"

# Ensure rpk is available
if ! command -v rpk >/dev/null 2>&1; then
  echo "[init_kafka] rpk is not available inside kafka-tools container."
  exit 1
fi

ensure_topic() {
  topic="$1"
  partitions="$2"
  replication="$3"

  if ${RPK_CMD} topic list 2>/dev/null | awk 'NR>1 {print $1}' | grep -Fxq "${topic}"; then
    echo "[init_kafka] Topic ${topic} already exists, skipping create."
    return
  fi

  echo "[init_kafka] Creating topic ${topic} with ${partitions} partitions and rf=${replication}..."
  ${RPK_CMD} topic create "${topic}" -p "${partitions}" -r "${replication}"
}

ensure_topic "doc_ingest" "${DOC_INGEST_PARTITIONS}" "${DOC_INGEST_REPLICATION}"
ensure_topic "doc_indexed" "${DOC_INDEXED_PARTITIONS}" "${DOC_INDEXED_REPLICATION}"
ensure_topic "doc_failed" "${DOC_FAILED_PARTITIONS}" "${DOC_FAILED_REPLICATION}"
# RAG search/answer workflow topics (hardcoded in worker)
ensure_topic "rag_search_request" "${RAG_PARTITIONS}" "${RAG_REPLICATION}"
ensure_topic "rag_search_result" "${RAG_PARTITIONS}" "${RAG_REPLICATION}"
ensure_topic "rag_answer_request" "${RAG_PARTITIONS}" "${RAG_REPLICATION}"
ensure_topic "rag_answer_result" "${RAG_PARTITIONS}" "${RAG_REPLICATION}"
ensure_topic "rag_failed" "${RAG_PARTITIONS}" "${RAG_REPLICATION}"

echo "[init_kafka] Done."
