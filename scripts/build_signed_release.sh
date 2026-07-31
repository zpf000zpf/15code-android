#!/usr/bin/env bash

set -euo pipefail

project_dir=$(cd "$(dirname "$0")/.." && pwd)
keystore_file="$project_dir/app/signing/15code-release.jks"
secret_file="$project_dir/app/signing/15code-release.secret"
gradle_bin=${GRADLE_BIN:-/home/zpf000zpf/.local/gradle/gradle-8.10.2/bin/gradle}

if [[ ! -s "$keystore_file" || ! -s "$secret_file" ]]; then
  echo "Release signing material is missing. See docs/release-signing.md." >&2
  exit 1
fi

export ANDROID_KEYSTORE_PATH="$keystore_file"
export ANDROID_KEYSTORE_PASSWORD
ANDROID_KEYSTORE_PASSWORD=$(openssl dgst -sha256 "$secret_file" | awk '{print $2}')
export ANDROID_KEY_ALIAS='15code-release'
export ANDROID_KEY_PASSWORD
ANDROID_KEY_PASSWORD=$(openssl dgst -sha512 "$secret_file" | awk '{print substr($2, 1, 64)}')

cd "$project_dir"
"$gradle_bin" assembleRelease bundleRelease
