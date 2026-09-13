"""Run admission and console checks on a prepared loopback-only Paper server."""

import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
import uuid

from run_velocity_probe import PROBE_CONFIG, PROBE_DOCUMENT


def offline_uuid(name):
    digest = hashlib.md5(("OfflinePlayer:" + name).encode()).digest()
    return str(uuid.UUID(bytes=digest, version=3))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--modules", required=True, type=Path)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    directory = project / ".run" / "paper-minimal"
    properties = dict(line.split("=", 1) for line in (directory / "server.properties").read_text().splitlines()
                      if "=" in line and not line.startswith("#"))
    assert properties.get("server-ip") == "127.0.0.1", "Only the loopback test server is allowed"
    assert properties.get("server-port") == "25592"
    assert properties.get("online-mode") == "false"
    assert properties.get("enable-rcon") == "false"
    assert properties.get("enable-query") == "false"
    with socket.socket() as check:
        assert check.connect_ex(("127.0.0.1", 25592)) != 0, "Test port already occupied"
    modules = args.modules.resolve()
    assert (modules / "minecraft-protocol").is_dir()
    assert (modules / "prismarine-nbt").is_dir()
    artifact = project / "platform-paper/build/libs/ConsentGate-Paper-0.1.0-prototype.jar"
    assert artifact.is_file(), "Build the Paper artifact first"
    data = directory / "plugins/ConsentGate"
    data.mkdir(parents=True, exist_ok=True)
    config_file = data / "config.yml"
    original_config = config_file.read_bytes() if config_file.exists() else None
    fixture_name = "probe-fixtures/" + uuid.uuid4().hex
    fixture = data / fixture_name
    documents = fixture / "documents"
    documents.mkdir(parents=True)
    database = fixture / "probe.db"
    config = PROBE_CONFIG.format(fixture=fixture_name).replace("timeout-seconds: 300", "timeout-seconds: 30")
    (documents / "agreement.yml").write_text(PROBE_DOCUMENT, encoding="utf-8")
    config_file.write_text(config, encoding="utf-8")
    shutil.copyfile(artifact, directory / "plugins/ConsentGate.jar")
    log_file = directory / "logs/latest.log"
    process = None

    def log():
        return log_file.read_text(encoding="utf-8", errors="replace") if log_file.exists() else ""

    def command(value, expected):
        offset = len(log())
        process.stdin.write(value + "\n")
        process.stdin.flush()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if expected in log()[offset:]:
                return
            if process.poll() is not None:
                raise RuntimeError("Test server exited")
            time.sleep(0.1)
        raise AssertionError("Missing console reply for " + value + ": " + expected)

    def probe(name, expected="accepted", hold=0, action="accept", on_dialog=None):
        child = subprocess.Popen(["node", str(project / "tools/probe_paper.cjs"), "--modules", str(modules),
                                 "--name", name, "--hold", str(hold), "--play-seconds", "1",
                                 "--expect", expected, "--action", action],
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        reports = []
        lines = []
        timer = threading.Timer(hold + 60, child.kill)
        timer.start()
        try:
            for line in child.stdout:
                lines.append(line)
                try:
                    report = json.loads(line)
                except ValueError:
                    continue
                reports.append(report)
                if report.get("event") == "dialog" and on_dialog is not None:
                    on_dialog()
                    on_dialog = None
            child.wait(timeout=5)
        finally:
            timer.cancel()
            child.stdout.close()
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=5)
        if child.returncode != 0 or not any(item.get("event") == "result" and item.get("passed") for item in reports):
            raise AssertionError("Paper probe failed:\n" + "".join(lines))
        print("PASS: Paper " + name + " " + expected + " (" + action + ")", flush=True)
        return reports

    def decisions(name):
        with closing(sqlite3.connect(database)) as connection:
            return [row[0] for row in connection.execute(
                "SELECT decision FROM cg_acceptance_events WHERE player_uuid=? ORDER BY decided_at", (offline_uuid(name),))]

    try:
        process = subprocess.Popen(["java", "-Xms256m", "-Xmx768m", "-Dterminal.jline=false",
                                    "-Dterminal.ansi=false", "-jar", "server.jar", "--nogui"],
                                   cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.STDOUT, text=True)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("Paper exited; inspect .run/paper-minimal/logs/latest.log")
            with socket.socket() as check:
                if check.connect_ex(("127.0.0.1", 25592)) == 0 and "Done (" in log():
                    break
            time.sleep(0.2)
        else:
            raise TimeoutError("Paper startup exceeded 90 seconds")
        assert "ConsentGate is enabled with sqlite storage." in log()
        command("consentgate validate", "Validation passed.")
        command("consentgate document", "test-agreement (probe-1): required; locales: en-US")
        command("consentgate document test-agreement en-US 2", "Acceptance must be stored before backend admission.")
        command("consentgate preview " + offline_uuid("ConsentPreview") + " en-US", "Preview queued")
        probe("ConsentPreview", "preview")
        assert decisions("ConsentPreview") == []
        probe("ConsentPreview")
        assert decisions("ConsentPreview") == ["granted"]
        command("consentgate preview " + offline_uuid("ConsentPreview"), "Preview queued")
        probe("ConsentPreview", "preview")
        assert decisions("ConsentPreview") == ["granted"]
        probe("ConsentPreview", "rejoin")
        command("consentgate preview " + offline_uuid("ConsentPreview"), "Preview queued")
        command("consentgate preview " + offline_uuid("ConsentPreview") + " cancel", "Preview cancelled")
        probe("ConsentPreview", "rejoin")
        command("consentgate preview " + offline_uuid("ConsentPreview") + " id-ID", "No exact id-ID translation")

        def pressure_check():
            target = offline_uuid("ConsentPressure")
            command("consentgate reset " + target, "Disconnect the player first")
            command("consentgate preview " + target, "Disconnect the player first")
            command("consentgate reload", "Reload is busy.")
            probe("ConsentOverflow", "unavailable")
            assert decisions("ConsentOverflow") == []

        probe("ConsentPressure", hold=8, on_dialog=pressure_check)
        probe("ConsentFlow")
        assert decisions("ConsentFlow") == ["granted"]
        probe("ConsentFlow", "rejoin")
        assert decisions("ConsentFlow") == ["granted"]
        player = offline_uuid("ConsentFlow")
        command("consentgate status " + player, "test-agreement (probe-1): accepted")
        command("consentgate reset " + player, "History was kept.")
        assert decisions("ConsentFlow") == ["granted", "withdrawn"]
        probe("ConsentFlow")
        assert decisions("ConsentFlow") == ["granted", "withdrawn", "granted"]
        document = documents / "agreement.yml"
        document.write_text(PROBE_DOCUMENT.replace("Local automated test content.", "Changed text."), encoding="utf-8")
        command("consentgate reload", "Document content changed without a version bump")
        document.write_text(PROBE_DOCUMENT.replace('version: "probe-1"', 'version: "probe-2"'), encoding="utf-8")
        command("consentgate reload", "ConsentGate reloaded.")
        probe("ConsentFlow")
        probe("ConsentLeave", "denied", action="leave")
        assert decisions("ConsentLeave") == []
        timeout_reports = probe("ConsentTimeout", "denied", hold=40)
        assert any("Consent request timed out or failed." in json.dumps(item) for item in timeout_reports)
        assert decisions("ConsentTimeout") == []
        print("PASS: Paper packaged admission, reconnect, reset history, reload, Leave, and timeout", flush=True)
    finally:
        if process is not None:
            if process.poll() is None:
                try:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=25)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    process.terminate()
                    process.wait(timeout=10)
            process.stdin.close()
        if original_config is None:
            config_file.unlink(missing_ok=True)
        else:
            config_file.write_bytes(original_config)
        print("Paper stopped; original configuration restored. Fixture: " + str(fixture), flush=True)


if __name__ == "__main__":
    main()
