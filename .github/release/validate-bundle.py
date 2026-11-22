#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--require-current-source", action="store_true")
args = parser.parse_args()
root = Path("delivery")
manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
if manifest.get("schema") != 1:
    raise SystemExit("Unsupported bundle manifest")

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

for key in ("apk", "testApk"):
    item = manifest[key]
    path = root / item["name"]
    if not path.is_file() or path.stat().st_size != item["size"] or sha256(path) != item["sha256"]:
        raise SystemExit(f"Bundle {key} digest/size mismatch")

identity = subprocess.check_output(["bash", ".github/release/source-identity.sh"], text=True).strip()
if args.require_current_source and manifest["sourceIdentity"] != identity:
    raise SystemExit("Refusing APK reuse: application source identity differs")

apk = root / manifest["apk"]["name"]
bt = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "35.0.0"
signature = subprocess.check_output(
    [bt / "apksigner", "verify", "--verbose", "--print-certs", apk], text=True
)
cert = re.search(r"^Signer #1 certificate SHA-256 digest: ([0-9a-f]+)$", signature, re.M)
if not cert or cert.group(1) != manifest["certificateSha256"]:
    raise SystemExit("Bundle signing certificate mismatch")
badging = subprocess.check_output([bt / "aapt", "dump", "badging", apk], text=True)
expected = (
    f"package: name='{manifest['applicationId']}' versionCode='{manifest['versionCode']}' "
    f"versionName='{manifest['versionName']}'"
)
if expected not in badging:
    raise SystemExit("Bundle application metadata mismatch")
if manifest["applicationId"] != "jp.xiaqui.artifact.store.preview":
    raise SystemExit("Unexpected applicationId")
if manifest["certificateSha256"] != "dcfd2c8122274ec4148473c96b1853989c8d22e58e445d6ec4889ad5b34dffb2":
    raise SystemExit("Unexpected delivery certificate")
print(json.dumps(manifest, ensure_ascii=False))
