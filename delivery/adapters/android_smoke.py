"""Pull-based, trusted-device test. Install the internal Play build on a dedicated tester device."""

import argparse
import json
import re
import shutil
import subprocess
import time
from xml.etree import ElementTree

from delivery.adapters import github as gh
from delivery.agents import config
from delivery.policy import artifact_id


def adb(serial, *args):
    return subprocess.check_output(
        ["adb", "-s", serial, *map(str, args)], text=True, timeout=35
    )


def foreground(serial, package):
    result = adb(serial, "shell", "am", "start", "-W", "-n", package + "/.MainActivity")
    if "Status: ok" not in result:
        raise RuntimeError("The Play-installed app did not start")


def ui(serial):
    # Use a device-local transient file. No player names, screenshots or account data leave the device.
    name = "/data/local/tmp/ctm-delivery-ui.xml"
    adb(serial, "shell", "uiautomator", "dump", name)
    try:
        return ElementTree.fromstring(adb(serial, "exec-out", "cat", name))
    finally:
        adb(serial, "shell", "rm", "-f", name)


def node(tree, package, label):
    return next(
        (
            n
            for n in tree.iter("node")
            if n.get("package") == package
            and label in (n.get("text"), n.get("content-desc"))
        ),
        None,
    )


def check(serial, package):
    foreground(serial, package)
    for _ in range(5):
        tree = ui(serial)
        target = node(tree, package, "Tournaments")
        if target is not None and node(tree, package, "Players") is not None:
            break
        time.sleep(1)
    else:
        raise RuntimeError("Expected Players and Tournaments on the home screen")
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", target.get("bounds", ""))
    if not bounds:
        raise RuntimeError("Missing bounds on the tournament navigation tab")
    x1, y1, x2, y2 = map(int, bounds.groups())
    adb(serial, "shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)
    for _ in range(5):
        tree = ui(serial)
        if (
            node(tree, package, "Create tournament") is not None
            or node(tree, package, "Create Tournament") is not None
        ):
            return
        time.sleep(1)
    raise RuntimeError(
        "The tournament list did not expose its create-tournament action"
    )


def run_once(serial):
    record = gh.Record("releases").data
    c = record.get("active")
    if not c or not c.get("sha256") or c.get("blocked") or c.get("smoke"):
        return
    # The release controller confirms internal availability before requesting this worker.
    marker = "smoke-" + str(c["build"])
    if marker not in record.get("notices", []):
        return
    pkg = config()["package"]
    installed = adb(serial, "shell", "pm", "list", "packages", "-i", pkg).splitlines()
    if not any(
        line.split() == ["package:" + pkg, "installer=com.android.vending"]
        for line in installed
    ):
        print("Waiting for the tester to install the app from Google Play.")
        return
    info = adb(serial, "shell", "dumpsys", "package", pkg)
    version = re.search(r"^\s*versionCode=(\d+)\b", info, re.MULTILINE)
    if not version or int(version.group(1)) != c["build"]:
        print("Waiting for the internal Play update to version", c["build"])
        return
    try:
        check(serial, pkg)
        result = "passed"
        evidence = f"Google Play installer and version {c['build']} verified. MainActivity opened and the tournament list was reachable. Native review card visibility was unverified."
    except RuntimeError as exc:
        result = "failed"
        evidence = str(exc)
    inputs = {
        "mode": "smoke",
        "artifact": json.dumps(artifact_id(c), separators=(",", ":")),
        "result": result,
        "evidence": evidence,
    }
    gh.api(
        gh.API + "/actions/workflows/release.yml/dispatches",
        "POST",
        {"ref": "main", "inputs": inputs},
    )
    print("Submitted", result, "for build", c["build"])


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument(
        "--serial", required=True, help="Dedicated internal tester ADB serial"
    )
    p.add_argument("--watch", action="store_true")
    args = p.parse_args()
    if not shutil.which("adb"):
        p.error("The Android platform-tools adb command must be on PATH")
    while True:
        try:
            run_once(args.serial)
        except (
            gh.GitHubError,
            subprocess.SubprocessError,
            ElementTree.ParseError,
        ):
            if not args.watch:
                raise
            print("Device or task host is unavailable. The worker will retry.")
        if not args.watch:
            break
        time.sleep(120)
