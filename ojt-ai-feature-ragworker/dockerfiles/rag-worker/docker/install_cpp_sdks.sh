#!/bin/bash

set -euo pipefail

echo "[install_cpp_sdks] Installing C++ SDKs (with AWSSDK via vcpkg)..."

VCPKG_ROOT=${VCPKG_ROOT:-/opt/vcpkg}

# Ensure basic deps are present (Dockerfile already installs most, keep light)
apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        zip \
        unzip \
        && rm -rf /var/lib/apt/lists/*

# Install vcpkg if missing
if [ ! -d "$VCPKG_ROOT" ]; then
    echo "[install_cpp_sdks] Cloning vcpkg into $VCPKG_ROOT"
    git clone --depth 1 https://github.com/microsoft/vcpkg "$VCPKG_ROOT"
    "$VCPKG_ROOT"/bootstrap-vcpkg.sh -disableMetrics
fi

# Install AWS SDK (S3) via vcpkg
echo "[install_cpp_sdks] Installing aws-sdk-cpp[s3] via vcpkg"
"$VCPKG_ROOT"/vcpkg install "aws-sdk-cpp[s3]":x64-linux --clean-after-build

echo "[install_cpp_sdks] Done. AWSSDK installed under $VCPKG_ROOT/installed/x64-linux"
