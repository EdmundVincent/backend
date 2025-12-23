#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "${SCRIPT_DIR}/.." && pwd)
PROJECT_DIR="${REPO_ROOT}/rag-worker"
BUILD_DIR="${PROJECT_DIR}/build"

usage() {
  cat <<'EOF'
Usage: test_rag_worker.sh [--skip-build]

Runs rag-worker's Postgres test mode. Assumes the docker compose stack
provides Postgres with the expected credentials (see README).

Options:
  --skip-build   Skip cmake/make and run existing build artifacts.
EOF
}

maybe_build() {
  if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    return
  fi

  mkdir -p "${BUILD_DIR}"
  cmake -S "${PROJECT_DIR}" -B "${BUILD_DIR}"
  cmake --build "${BUILD_DIR}"
}

run_test() {
  pushd "${BUILD_DIR}" >/dev/null
  ./rag-worker-dev --test-pg
  popd >/dev/null
}

main() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-build) SKIP_BUILD=1 ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
    shift
  done

  maybe_build
  run_test
}

main "$@"
