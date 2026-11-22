#!/usr/bin/env python3
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

apk = Path("delivery/ReleaseStore-preview.apk")
test_apk = Path("delivery/ReleaseStore-preview-androidTest.apk")
bt = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "35.0.0"
badging = subprocess.check_output([bt / "aapt", "dump", "badging", apk], text=True)
match = re.search(
    r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'",
    badging,
    re.M,
)
if not match:
    raise SystemExit("Cannot read application metadata")
signature = subprocess.check_output(
    [bt / "apksigner", "verify", "--verbose", "--print-certs", apk], text=True
)
cert = re.search(r"^Signer #1 certificate SHA-256 digest: ([0-9a-f]+)$", signature, re.M)
if not cert:
    raise SystemExit("Cannot read signing certificate")

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

manifest = {
    "schema": 1,
    "sourceSha": os.environ["GITHUB_SHA"],
    "sourceIdentity": subprocess.check_output(
        ["bash", ".github/release/source-identity.sh"], text=True
    ).strip(),
    "applicationId": match.group(1),
    "versionCode": int(match.group(2)),
    "versionName": match.group(3),
    "certificateSha256": cert.group(1),
    "apk": {"name": apk.name, "sha256": sha256(apk), "size": apk.stat().st_size},
    "testApk": {
        "name": test_apk.name,
        "sha256": sha256(test_apk),
        "size": test_apk.stat().st_size,
    },
}
Path("delivery/manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
print(json.dumps(manifest, ensure_ascii=False))
