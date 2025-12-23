#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "${SCRIPT_DIR}/.." && pwd)
PROJECT_DIR="${REPO_ROOT}/rag-worker"
BUILD_DIR="${PROJECT_DIR}/build"

DOC_ID=${DOC_ID:-"00000000-0000-0000-0000-000000000001"}
SKIP_BUILD=${SKIP_BUILD:-0}

usage() {
  cat <<'EOF'
Usage: test_embed.sh [--doc-id <id>] [--skip-build]

Runs rag-worker embedding against a document id.
Requires Azure OpenAI embedding env vars and Qdrant endpoint to be set.

Env/Options:
  --doc-id <id>   Override DOC_ID (default: 00000000-0000-0000-0000-000000000001)
  --skip-build    Skip cmake/make and run existing binaries
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

run_embed() {
  pushd "${BUILD_DIR}" >/dev/null
  ./rag-worker-dev --embed-doc "${DOC_ID}"
  popd >/dev/null
}

main() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --doc-id) DOC_ID="$2"; shift ;;
      --skip-build) SKIP_BUILD=1 ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
    shift
  done

  maybe_build
  run_embed
}

main "$@"
