#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "${SCRIPT_DIR}/.." && pwd)
PROJECT_DIR="${REPO_ROOT}/rag-worker"
BUILD_DIR="${PROJECT_DIR}/build"

QUESTION=${QUESTION:-"demo 文档的用途是什么？"}
TENANT_ID=${TENANT_ID:-"tenant-001"}
KB_ID=${KB_ID:-"kb-001"}
TOPK=${TOPK:-5}
SKIP_BUILD=${SKIP_BUILD:-0}

usage() {
  cat <<'EOF'
Usage: test_answer.sh [--question "<text>"] [--tenant <id>] [--kb <id>] [--topk <n>] [--skip-build]

Runs rag-worker answer flow using Azure OpenAI chat.
Requires embedding data to exist in Qdrant and Azure OpenAI chat env vars.

Env/Options:
  --question "<text>"  Override QUESTION (default: demo 文档的用途是什么？)
  --tenant <id>        Override TENANT_ID (default: tenant-001)
  --kb <id>            Override KB_ID (default: kb-001)
  --topk <n>           Override TOPK (default: 5)
  --skip-build         Skip cmake/make and run existing binaries
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

run_answer() {
  pushd "${BUILD_DIR}" >/dev/null
  ./rag-worker-dev --answer "${QUESTION}" --tenant "${TENANT_ID}" --kb "${KB_ID}" --topk "${TOPK}"
  popd >/dev/null
}

main() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --question) QUESTION="$2"; shift ;;
      --tenant) TENANT_ID="$2"; shift ;;
      --kb) KB_ID="$2"; shift ;;
      --topk) TOPK="$2"; shift ;;
      --skip-build) SKIP_BUILD=1 ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
    shift
  done

  maybe_build
  run_answer
}

main "$@"
