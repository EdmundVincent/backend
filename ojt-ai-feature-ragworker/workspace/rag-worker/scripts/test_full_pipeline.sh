#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "${SCRIPT_DIR}/.." && pwd)
PROJECT_DIR="${REPO_ROOT}/rag-worker"
BUILD_DIR="${PROJECT_DIR}/build"

DOC_ID=${DOC_ID:-"00000000-0000-0000-0000-000000000001"}
TENANT_ID=${TENANT_ID:-"tenant-001"}
KB_ID=${KB_ID:-"kb-001"}
QUESTION=${QUESTION:-"demo 文档的用途是什么？"}
QUERY=${QUERY:-"demo 文档中的关键词"}
TOPK=${TOPK:-5}
SKIP_BUILD=${SKIP_BUILD:-0}

usage() {
  cat <<'EOF'
Usage: test_full_pipeline.sh [options]

Runs an end-to-end pipeline:
  1) produce demo ingest event
  2) consume once (ingest + persist)
  3) embed doc into Qdrant
  4) search
  5) answer

Assumes docker compose stack (Kafka/Redpanda, Postgres, MinIO, Qdrant) is running
and Azure OpenAI env vars are set for both embedding and chat.

Options/Env:
  --doc-id <id>     Override DOC_ID (default: 00000000-0000-0000-0000-000000000001)
  --tenant <id>     Override TENANT_ID (default: tenant-001)
  --kb <id>         Override KB_ID (default: kb-001)
  --question "<q>"  Override QUESTION (default: demo 文档的用途是什么？)
  --query "<q>"     Override QUERY for search (default: demo 文档中的关键词)
  --topk <n>        Override TOPK (default: 5)
  --skip-build      Skip cmake/make and run existing binaries
EOF
}

maybe_build() {
  if [[ "${SKIP_BUILD}" == "1" ]]; then
    return
  fi
  mkdir -p "${BUILD_DIR}"
  cmake -S "${PROJECT_DIR}" -B "${BUILD_DIR}"
  cmake --build "${BUILD_DIR}"
}

run_pipeline() {
  pushd "${BUILD_DIR}" >/dev/null

  echo "[pipeline] produce demo ingest"
  ./rag-worker-dev --produce-demo

  echo "[pipeline] consume once"
  ./rag-worker-dev --consume-once

  echo "[pipeline] embed doc ${DOC_ID}"
  ./rag-worker-dev --embed-doc "${DOC_ID}"

  echo "[pipeline] search"
  ./rag-worker-dev --search "${QUERY}" --tenant "${TENANT_ID}" --kb "${KB_ID}" --topk "${TOPK}"

  echo "[pipeline] answer"
  ./rag-worker-dev --answer "${QUESTION}" --tenant "${TENANT_ID}" --kb "${KB_ID}" --topk "${TOPK}"

  popd >/dev/null
}

main() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --doc-id) DOC_ID="$2"; shift ;;
      --tenant) TENANT_ID="$2"; shift ;;
      --kb) KB_ID="$2"; shift ;;
      --question) QUESTION="$2"; shift ;;
      --query) QUERY="$2"; shift ;;
      --topk) TOPK="$2"; shift ;;
      --skip-build) SKIP_BUILD=1 ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
    shift
  done

  maybe_build
  run_pipeline
}

main "$@"
