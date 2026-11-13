#!/usr/bin/env bash
set -euo pipefail
manifest=.github/release/accepted.json
version=$(jq -er '.version' "$manifest")
code=$(jq -er '.versionCode' "$manifest")
source_sha=$(jq -er '.source' "$manifest")
preview=$(jq -er '.preview' "$manifest")
size=$(jq -er '.size' "$manifest")
hash=$(jq -er '.sha256' "$manifest")
cert=$(jq -er '.certificate' "$manifest")
package=$(jq -er '.applicationId' "$manifest")
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$source_sha" =~ ^[0-9a-f]{40}$ ]]
git fetch origin main --quiet
git merge-base --is-ancestor "$source_sha" origin/main
tag="v$version"
apk="ReleaseStore-$version.apk"
repo="$GITHUB_REPOSITORY"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
gh release download "$preview" -R "$repo" -p ReleaseStore-preview.apk -O "$apk"
test "$(stat -c %s "$apk")" = "$size"
echo "$hash  $apk" | sha256sum -c
bt="$ANDROID_HOME/build-tools/35.0.0"
"$bt/apksigner" verify --verbose --print-certs "$apk" > signature
grep -Fx "Signer #1 certificate SHA-256 digest: $cert" signature
grep -F 'Verified using v1 scheme (JAR signing): true' signature
grep -F 'Verified using v2 scheme (APK Signature Scheme v2): true' signature
"$bt/aapt" dump badging "$apk" > badging
grep -F "package: name='$package' versionCode='$code' versionName='$version'" badging
if [[ "$1" == verify ]]; then exit 0; fi
[[ "$1" == publish && "$GITHUB_REF" == refs/heads/main ]]
if gh release view "$tag" -R "$repo" --json isDraft,isPrerelease,assets > existing.json; then
  jq -e --arg name "$apk" '.isPrerelease == false and (.assets|length == 1 and .[0].name == $name)' existing.json
  gh release download "$tag" -R "$repo" -p "$apk" -O existing.apk
  cmp "$apk" existing.apk
  if jq -e '.isDraft == false' existing.json; then exit 0; fi
else
  # A tag without the matching Release belongs to its owner.
  refs=$(gh api "repos/$repo/git/matching-refs/tags/$tag")
  jq -e --arg ref "refs/tags/$tag" 'all(.[]; .ref != $ref)' <<<"$refs"
  gh release create "$tag" "$apk" -R "$repo" --target "$GITHUB_SHA" --draft \
    --title "Release Store $version" --notes "Device-accepted $version ($code). APK build: $source_sha. SHA-256: $hash. Signing certificate: $cert."
  gh release download "$tag" -R "$repo" -p "$apk" -O existing.apk
  cmp "$apk" existing.apk
fi
gh release edit "$tag" -R "$repo" --draft=false --latest
