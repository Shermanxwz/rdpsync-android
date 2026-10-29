#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/native-env.sh"
# Supported OpenSSL 3.5 LTS, pinned source and SHA256 from openssl-library.org/source/.
OPENSSL_VERSION=3.5.9
OPENSSL_SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
ARCHIVE="$BUILD_DIR/openssl-$OPENSSL_VERSION.tar.gz"
SOURCE="$BUILD_DIR/openssl-$OPENSSL_VERSION"
if [[ ! -f "$ARCHIVE" ]]; then
    curl --fail --location --retry 3 "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" -o "$ARCHIVE"
fi
echo "$OPENSSL_SHA256  $ARCHIVE" | sha256sum --check
if [[ ! -d "$SOURCE" ]]; then tar -xzf "$ARCHIVE" -C "$BUILD_DIR"; fi
mkdir -p "$BUILD_DIR/openssl-build-$BUILD_ARCH"
cd "$BUILD_DIR/openssl-build-$BUILD_ARCH"
export CC="${CLANG_TARGET}${API_LEVEL}-clang"
export AR=llvm-ar RANLIB=llvm-ranlib
# Static OpenSSL avoids loading versioned ELF sonames that Android packaging renames.
# Assembly is enabled; NDK r28 produces flexible-page-size compatible shared dependents.
"$SOURCE/Configure" "$OPENSSL_TARGET" -D__ANDROID_API__=$API_LEVEL --prefix="$OPENSSL_OUT" --libdir=lib \
    no-shared no-tests no-apps no-docs no-module -fPIC -O2
make -j"$JOBS"
make install_sw
