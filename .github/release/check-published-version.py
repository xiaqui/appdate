#!/usr/bin/env python3
"""Fail closed if a different APK reuses or decreases the published versionCode.

The legacy maximum is committed once. New previews publish a small signed-APK
manifest, so subsequent gates paginate Release metadata and download JSON only,
not every historical APK.
"""
import hashlib
import json
import os
import urllib.error
import urllib.request
from pathlib import Path

repo = os.environ["GITHUB_REPOSITORY"]
token = os.environ["GH_TOKEN"]
current = json.loads(Path("delivery/manifest.json").read_text(encoding="utf-8"))
baseline = json.loads(Path(".github/release/published-baseline.json").read_text(encoding="utf-8"))

def request(url, accept="application/vnd.github+json"):
    return urllib.request.Request(url, headers={
        "Authorization": "Bearer " + token,
        "Accept": accept,
        "X-GitHub-Api-Version": "2022-11-28",
    })

def pages(url):
    while url:
        with urllib.request.urlopen(request(url), timeout=30) as response:
            yield json.load(response)
            links = response.headers.get("Link", "")
        url = None
        for part in links.split(","):
            if 'rel="next"' in part:
                url = part[part.index("<") + 1:part.index(">")]
                break

records = [baseline]
api = f"https://api.github.com/repos/{repo}/releases?per_page=100"
for releases in pages(api):
    for release in releases:
        if release.get("draft"):
            continue
        for asset in release.get("assets", []):
            if not asset["name"].endswith("-preview.json"):
                continue
            with urllib.request.urlopen(
                request(asset["url"], "application/octet-stream"), timeout=30
            ) as response:
                body = response.read()
            digest = asset.get("digest")
            if digest and digest != "sha256:" + hashlib.sha256(body).hexdigest():
                raise SystemExit("Published manifest digest mismatch: " + asset["name"])
            record = json.loads(body)
            apk_name = record.get("releaseApkName")
            matching_apks = [item for item in release.get("assets", []) if item["name"] == apk_name]
            if not apk_name or len(matching_apks) != 1:
                raise SystemExit("Published manifest has no unique APK Asset: " + asset["name"])
            published_apk = matching_apks[0]
            if published_apk.get("digest") != "sha256:" + record["apk"]["sha256"]:
                raise SystemExit("Published APK digest does not match manifest: " + apk_name)
            if published_apk.get("size") != record["apk"]["size"]:
                raise SystemExit("Published APK size does not match manifest: " + apk_name)
            records.append(record)

for prior in records:
    prior_id = prior["applicationId"]
    prior_cert = prior["certificateSha256"]
    prior_code = int(prior.get("maxVersionCode", prior.get("versionCode")))
    prior_hashes = prior.get("apkSha256")
    if prior_hashes is None:
        prior_hashes = [prior["apk"]["sha256"]]
    if prior_id != current["applicationId"] or prior_cert != current["certificateSha256"]:
        continue
    if current["apk"]["sha256"] in prior_hashes:
        continue
    if current["versionCode"] <= prior_code:
        raise SystemExit(
            f"Refusing delivery: different APK uses versionCode {current['versionCode']} "
            f"but published maximum is {prior_code}"
        )
print(f"Version gate passed: {current['versionName']} ({current['versionCode']})")
