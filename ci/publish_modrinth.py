"""Publish the built HungerBridge jars to Modrinth (the only release channel).

    MODRINTH_TOKEN=... python ci/publish_modrinth.py --version 5-alpha.1 --changelog CHANGELOG.md --artifacts release/

``--artifacts`` contains one directory per build target, each with the jar and the
``target.json`` written by the build job: ``{"loader": "paper", "minecraft": "26.3"}``.
Loaders and game versions come from that metadata, never from file names. Each jar becomes
one Modrinth version (paper jars: loaders paper, purpur, folia; fabric jars: fabric). A
version that already exists for the same loaders and game version is skipped, so the job
can be re-run. ``--dry-run`` prints what would be uploaded without a token.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

API = "https://api.modrinth.com/v2"
PROJECT_ID = "LYkoD1S0"   # https://modrinth.com/project/hungerbridge
USER_AGENT = "HungerBridge-CI-release/1.0 (+https://github.com/HungerNet/HungerBridge)"
LOADERS = {"paper": ["paper", "purpur", "folia"], "fabric": ["fabric"]}
NAMES = {"paper": "Paper/Purpur/Folia", "fabric": "Fabric"}


def versionType(version: str) -> str:
    lowered = version.lower()
    return "alpha" if "alpha" in lowered else "beta" if ("beta" in lowered or "rc" in lowered) else "release"


def firstSection(markdown: str) -> str:
    """The newest release's notes: the first "## " section of CHANGELOG.md."""
    sections = markdown.split("\n## ")
    return ("## " + sections[1]).strip() if len(sections) > 1 else markdown.strip()


def request(method: str, url: str, token: str = None, body: bytes = None, content_type: str = None):
    headers = {"User-Agent": USER_AGENT}
    if token:
        headers["Authorization"] = token
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Modrinth {method} {url}: HTTP {error.code}: {error.read().decode(errors='replace')[:500]}")


def exists(version: str, loaders: list, minecraft: str) -> bool:
    query = urllib.parse.urlencode({"loaders": json.dumps(loaders), "game_versions": json.dumps([minecraft])})
    return any(v["version_number"] == version for v in request("GET", f"{API}/project/{PROJECT_ID}/version?{query}"))


def multipart(data: dict, jar: Path):
    boundary = uuid.uuid4().hex
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\nContent-Type: application/json\r\n\r\n'.encode()
        + json.dumps(data).encode() + b"\r\n",
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{jar.name}"\r\n'
        f"Content-Type: application/java-archive\r\n\r\n".encode() + jar.read_bytes() + b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", required=True)
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--changelog", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    token = os.environ.get("MODRINTH_TOKEN")
    if not token and not args.dry_run:
        raise SystemExit("MODRINTH_TOKEN is not set (repository secret)")
    changelog = firstSection(args.changelog.read_text(encoding="utf-8")) if args.changelog else ""
    targets = sorted(args.artifacts.glob("*/target.json"))
    if not targets:
        raise SystemExit(f"no target.json found under {args.artifacts}")

    for meta_file in targets:
        meta = json.loads(meta_file.read_text())
        jars = [j for j in meta_file.parent.glob("*.jar") if not j.name.endswith(("-sources.jar", "-dev.jar"))]
        if len(jars) != 1:
            raise SystemExit(f"{meta_file.parent}: expected exactly one jar, found {[j.name for j in jars]}")
        loaders = LOADERS[meta["loader"]]
        data = {
            "project_id": PROJECT_ID,
            "name": f"HungerBridge {args.version} ({NAMES[meta['loader']]} {meta['minecraft']})",
            "version_number": args.version,
            "changelog": changelog,
            "dependencies": [],
            "game_versions": [meta["minecraft"]],
            "version_type": versionType(args.version),
            "loaders": loaders,
            "featured": False,
            "status": "listed",
            "file_parts": ["file"],
            "primary_file": "file",
        }
        label = f"{meta['loader']} {meta['minecraft']} ({jars[0].name})"
        if args.dry_run:
            print(f"would publish {label}: {json.dumps({k: v for k, v in data.items() if k != 'changelog'})}")
            continue
        if exists(args.version, loaders, meta["minecraft"]):
            print(f"skip {label}: {args.version} is already on Modrinth")
            continue
        body, content_type = multipart(data, jars[0])
        created = request("POST", f"{API}/version", token, body, content_type)
        print(f"published {label}: https://modrinth.com/project/hungerbridge/version/{created['id']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
