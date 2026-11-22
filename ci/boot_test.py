"""Boot a real Minecraft server with a HungerBridge jar and verify it end to end.

    python ci/boot_test.py --loader paper --minecraft 26.3 --jar build/HungerBridge-paper-26.3-5-alpha.1.jar \
        --java "$JAVA_HOME_25_X64/bin/java" --hungerbridgepy ../HungerBridgePy

Steps: download the server (Paper/Folia: PaperMC Fill API, Purpur: Purpur API, Fabric:
Fabric meta), install the jar into plugins/ or mods/, accept the EULA, start the server and
wait for "Done", then over the real server console:

1. create a token with ``hb token create`` and redeem the printed pickup;
2. ``runCommand("seed", False)``: the feedback must NOT appear in the console output;
3. ``runCommand("seed", show_console=True)``: the feedback must appear;
4. ``log(level="CUSTOMLEVEL", thread="CustomThread", logger="CustomLogger")``: the console
   line has the loader's layout with ``[CustomLogger]``;
5. create a second token and run HungerBridgePy's live suite (tests/test_live.py) against
   the server, which also checks streaming, resume, normalization and errors;
6. stop the server with the ``stop`` console command.

Requires only the standard library plus an installed HungerBridgePy. Exit code 0 = pass.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
from pathlib import Path

USER_AGENT = "HungerBridge-CI-boot-test/1.0 (+https://github.com/HungerNet/HungerBridge)"
PICKUP = re.compile(r"(/pickup/[0-9a-f-]+\?passkey=[0-9a-f]+)")
ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
PORT = 1913


def getJson(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def download(url: str, target: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response, open(target, "wb") as out:
        shutil.copyfileobj(response, out)


def serverJarUrl(loader: str, minecraft: str) -> str:
    if loader in ("paper", "folia"):
        build = getJson(f"https://fill.papermc.io/v3/projects/{loader}/versions/{minecraft}/builds/latest")
        return build["downloads"]["server:default"]["url"]
    if loader == "purpur":
        return f"https://api.purpurmc.org/v2/purpur/{minecraft}/latest/download"
    if loader == "fabric":
        fabric_loader = next(v["loader"]["version"] for v in getJson(f"https://meta.fabricmc.net/v2/versions/loader/{minecraft}")
                             if v["loader"].get("stable", True))
        installer = next(v["version"] for v in getJson("https://meta.fabricmc.net/v2/versions/installer") if v.get("stable"))
        return f"https://meta.fabricmc.net/v2/versions/loader/{minecraft}/{fabric_loader}/{installer}/server/jar"
    raise SystemExit(f"unknown loader {loader}")


class Server:
    """The server process; stdout is mirrored to console.log and kept in memory."""

    def __init__(self, workdir: Path, java: str, memory: str):
        self.workdir = workdir
        self.lines: list = []
        self.log = open(workdir / "console.log", "w", encoding="utf-8")
        self.process = subprocess.Popen([java, f"-Xmx{memory}", "-jar", "server.jar", "nogui"], cwd=workdir,
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        text=True, encoding="utf-8", errors="replace", bufsize=1)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.process.stdout:
            clean = ANSI.sub("", line.rstrip("\n"))
            self.lines.append(clean)
            self.log.write(clean + "\n")
            self.log.flush()

    def send(self, command: str) -> None:
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def mark(self) -> int:
        return len(self.lines)

    def since(self, mark: int) -> list:
        return self.lines[mark:]

    def waitFor(self, pattern, timeout: float, mark: int = 0):
        regex = re.compile(pattern)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for line in self.lines[mark:]:
                found = regex.search(line)
                if found:
                    return found
            if self.process.poll() is not None:
                raise SystemExit(f"server exited with {self.process.returncode}")
            time.sleep(0.25)
        raise SystemExit(f"timed out after {timeout}s waiting for {pattern!r}")

    def stop(self, timeout: float = 90) -> None:
        if self.process.poll() is None:
            self.send("stop")
            try:
                self.process.wait(timeout)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.log.close()


def createPickup(server: Server, token_id: str) -> str:
    mark = server.mark()
    server.send(f"hb token create {token_id} admin")
    return f"http://127.0.0.1:{PORT}" + server.waitFor(PICKUP.pattern, 30, mark).group(1)


def check(condition: bool, message: str) -> None:
    print(("PASS " if condition else "FAIL ") + message, flush=True)
    if not condition:
        raise SystemExit(1)


def consoleChecks(server: Server, loader: str) -> None:
    from hungerbridge import BridgeClient

    client = BridgeClient.fromPickup(createPickup(server, "ci-boot"))

    mark = server.mark()
    output = client.runCommand("seed", False)
    time.sleep(3)
    check(output.startswith("Seed: ["), f"runCommand returns the feedback ({output!r})")
    leaked = [line for line in server.since(mark) if "Seed: [" in line]
    check(not leaked, f"show_console=False keeps the feedback off the console ({leaked})")

    mark = server.mark()
    client.runCommand("seed", show_console=True)
    server.waitFor(r"Seed: \[", 15, mark)
    check(True, "show_console=True echoes the feedback to the console")

    marker = f"boot-{uuid.uuid4().hex[:8]}"
    mark = server.mark()
    client.log(message=f"{marker} example message", level="CUSTOMLEVEL", thread="CustomThread", logger="CustomLogger")
    if loader == "fabric":   # vanilla layout: [HH:mm:ss] [thread/LEVEL]: message
        pattern = rf"^\[\d\d:\d\d:\d\d\] \[CustomThread/CUSTOMLEVEL\]: \[CustomLogger\] {marker} example message$"
    else:                    # Paper layout: [HH:mm:ss LEVEL]: [logger] message
        pattern = rf"^\[\d\d:\d\d:\d\d CUSTOMLEVEL\]: \[CustomLogger\] {marker} example message$"
    line = server.waitFor(pattern, 15, mark).group(0)
    check(True, f"custom logger line on the console: {line}")
    client.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--loader", required=True, choices=["paper", "purpur", "folia", "fabric"])
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--jar", required=True, type=Path, help="the HungerBridge jar to test")
    parser.add_argument("--java", default="java")
    parser.add_argument("--memory", default="2G")
    parser.add_argument("--workdir", type=Path, default=Path("boot-server"))
    parser.add_argument("--hungerbridgepy", type=Path, required=True, help="HungerBridgePy checkout (for tests/test_live.py)")
    parser.add_argument("--boot-timeout", type=float, default=600)
    args = parser.parse_args()

    workdir = args.workdir.resolve()
    workdir.mkdir(parents=True, exist_ok=True)
    download(serverJarUrl(args.loader, args.minecraft), workdir / "server.jar")
    (workdir / "eula.txt").write_text("eula=true\n")
    # offline mode for a headless test, so only listen on localhost
    (workdir / "server.properties").write_text(
        "server-ip=127.0.0.1\nonline-mode=false\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\n")
    plugin = args.loader != "fabric"
    target = workdir / ("plugins" if plugin else "mods")
    target.mkdir(exist_ok=True)
    shutil.copy(args.jar, target / args.jar.name)
    config = workdir / ("plugins/HungerBridge" if plugin else "config/HungerBridge")
    config.mkdir(parents=True, exist_ok=True)
    (config / "policies.yaml").write_text('policies:\n  - id: admin\n    permissions: ["*"]\n')

    server = Server(workdir, args.java, args.memory)
    try:
        server.waitFor(r"Done \(", args.boot_timeout)
        server.waitFor(r"HTTP API listening on 127\.0\.0\.1:1913", 60)
        consoleChecks(server, args.loader)
        env = dict(os.environ, HB_PICKUP_URL=createPickup(server, "ci-live"), HB_EXPECT_MC=args.minecraft,
                   HB_EXPECT_PLATFORM={"purpur": "paper"}.get(args.loader, args.loader))
        result = subprocess.run([sys.executable, "-m", "pytest", "-q", "-m", "live", "tests/test_live.py"],
                                cwd=args.hungerbridgepy, env=env)
        check(result.returncode == 0, "HungerBridgePy live suite")
        return 0
    finally:
        server.stop()


if __name__ == "__main__":
    sys.exit(main())
