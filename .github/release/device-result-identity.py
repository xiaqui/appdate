#!/usr/bin/env python3
import hashlib
import json
import re
import subprocess
import sys

SCHEMA = 1


def show(ref: str, path: str) -> str:
    return subprocess.check_output(
        ["git", "show", f"{ref}:{path}"], text=True
    )


def normalized_device_job(workflow: str) -> str:
    match = re.search(r"(?ms)^  device:\n(.*?)(?=^  preview:\n)", workflow)
    if not match:
        raise SystemExit("Cannot locate device job")
    retained = []
    for line in match.group(1).splitlines():
        if re.match(r"^    (needs|if):", line):
            continue
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        retained.append(line.rstrip())
    return "\n".join(retained) + "\n"


def identity(ref: str) -> str:
    payload = {
        "schema": SCHEMA,
        "deviceJob": normalized_device_job(
            show(ref, ".github/workflows/android.yml")
        ),
        "deviceChecks": show(ref, ".github/workflows/device-checks.sh"),
        "bundleValidation": show(ref, ".github/release/validate-bundle.py"),
    }
    encoded = json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()
    return hashlib.sha256(encoded).hexdigest()


if len(sys.argv) != 3:
    raise SystemExit("usage: device-result-identity.py SOURCE_REF CURRENT_REF")

source, current = sys.argv[1:]
source_identity = identity(source)
current_identity = identity(current)
result = {
    "schema": SCHEMA,
    "sourceRef": source,
    "currentRef": current,
    "sourceIdentity": source_identity,
    "currentIdentity": current_identity,
    "matches": source_identity == current_identity,
}
print(json.dumps(result, ensure_ascii=False))
if not result["matches"]:
    raise SystemExit("Device test conditions differ")
