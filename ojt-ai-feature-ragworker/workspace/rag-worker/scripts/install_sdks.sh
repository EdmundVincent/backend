#!/usr/bin/env bash
set -euo pipefail

# Installs the core SDKs and tooling needed for C++ development:
# - AWS SDK for C++ (S3) via vcpkg for MinIO-compatible object storage.
# - libpq/libpqxx via vcpkg for PostgreSQL access.
# - pgvector extension compiled from source for embedding support.
# - vcpkg package manager itself for future C++ dependency management.
VCPKG_ROOT="${VCPKG_ROOT:-/opt/vcpkg}"

install_base_packages() {
  apt-get update
  apt-get install -y --no-install-recommends \
    build-essential cmake pkg-config ninja-build git curl ca-certificates bison flex autoconf \
    postgresql postgresql-client postgresql-contrib postgresql-server-dev-all \
    zlib1g-dev libzstd-dev uuid-dev unzip librdkafka-dev
}

install_vcpkg() {
  if [ ! -d "${VCPKG_ROOT}" ]; then
    git clone --depth 1 https://github.com/microsoft/vcpkg.git "${VCPKG_ROOT}"
    "${VCPKG_ROOT}/bootstrap-vcpkg.sh" -disableMetrics
  fi

  ln -sf "${VCPKG_ROOT}/vcpkg" /usr/local/bin/vcpkg
}

install_vcpkg_packages() {
  # aws-sdk-cpp[s3] gives us S3-compatible clients (MinIO), libpqxx is PostgreSQL C++ bindings.
  # nlohmann-json provides JSON parsing; required during CMake configure.
  vcpkg install aws-sdk-cpp[s3]:x64-linux libpqxx:x64-linux nlohmann-json:x64-linux
}

install_pgvector() {
  local tmp_dir="/tmp/pgvector"
  rm -rf "${tmp_dir}"
  git clone --depth 1 https://github.com/pgvector/pgvector.git "${tmp_dir}"
  make -C "${tmp_dir}"
  make -C "${tmp_dir}" install
  rm -rf "${tmp_dir}"
}

cleanup() {
  apt-get clean
  rm -rf /var/lib/apt/lists/*
}

main() {
  install_base_packages
  install_vcpkg
  install_vcpkg_packages
  install_pgvector
  cleanup
}

main "$@"
