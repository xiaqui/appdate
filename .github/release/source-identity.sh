#!/usr/bin/env bash
set -euo pipefail

# Only inputs that can change the application or instrumentation APKs belong here.
# Workflow, documentation and release-script edits intentionally keep this identity.
git ls-files -s -- app settings.gradle.kts gradle.properties \
  | LC_ALL=C sort \
  | sha256sum \
  | cut -d' ' -f1
