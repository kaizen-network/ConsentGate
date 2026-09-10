"""Run the prepared loopback-only proxy and stop it after the wire checks."""

import pathlib
from contextlib import closing
import shutil
import socket
import sqlite3
import subprocess
import sys
import time
import tomllib
import uuid

import probe_velocity


def clean_database(database, required=True):
    for candidate in (database, database.with_suffix(".db-wal"), database.with_suffix(".db-shm")):
        deadline = time.monotonic() + 5
        while True:
            try:
                candidate.unlink(missing_ok=True)
                break
            except PermissionError:
                if time.monotonic() >= deadline:
                    if required:
                        raise
                    print(f"WARN: could not remove disposable test file {candidate}", file=sys.stderr)
                    break
                time.sleep(0.1)


def main():
    project = pathlib.Path(__file__).resolve().parents[1]
    directory = project / ".run" / "velocity"
    artifact = project / "platform-velocity" / "build" / "libs" / "ConsentGate-Velocity-0.1.0-prototype.jar"
    assert artifact.is_file(), "Build the Velocity artifact before running the probe"
    shutil.copyfile(artifact, directory / "plugins" / "ConsentGate.jar")
    data_directory = directory / "plugins" / "consentgate"
    fixture_name = "probe-fixtures/" + uuid.uuid4().hex
    fixture = data_directory / fixture_name
    documents = fixture / "documents"
    documents.mkdir(parents=True, exist_ok=True)
    database = fixture / "probe.db"
    clean_database(database)
    (data_directory / "config.yml").write_text(PROBE_CONFIG.format(fixture=fixture_name), encoding="utf-8")
    (documents / "agreement.yml").write_text(PROBE_DOCUMENT, encoding="utf-8")
    with (directory / "velocity.toml").open("rb") as source:
        config = tomllib.load(source)
    assert config["bind"] == "127.0.0.1:25590", "Only the loopback test proxy is allowed"
    assert config["online-mode"] is False
    assert config["advanced"]["compression-threshold"] == -1
    assert config["servers"]["backend"] == "127.0.0.1:25591"
    assert config["servers"]["try"] == ["backend"]
    with socket.socket() as check:
        assert check.connect_ex(("127.0.0.1", 25590)) != 0, "Test port already occupied"
    process = subprocess.Popen(
        ["java", "-Xms128m", "-Xmx256m", "-Dterminal.jline=false", "-jar", "velocity.jar"],
        cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
        text=True,
    )
    try:
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("Proxy exited; inspect .run/velocity/logs/latest.log")
            with socket.socket() as check:
                if check.connect_ex(("127.0.0.1", 25590)) == 0:
                    break
            time.sleep(0.2)
        else:
            raise TimeoutError("Proxy startup exceeded 30 seconds")
        def admin_check(backend, name, client_id):
            with closing(sqlite3.connect(database)) as connection:
                player_id = connection.execute("SELECT player_uuid FROM cg_acceptance_state").fetchone()[0]
            log_file = directory / "logs" / "latest.log"

            def command(text, expected):
                offset = len(log_file.read_text(encoding="utf-8"))
                process.stdin.write(text + "\n")
                process.stdin.flush()
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    if expected in log_file.read_text(encoding="utf-8")[offset:]:
                        return
                    time.sleep(0.1)
                raise AssertionError("Admin command did not return the expected response: " + text)

            command("consentgate status " + player_id, "test-agreement (probe-1): accepted")
            command("consentgate validate", "Validation passed.")
            document_file = documents / "agreement.yml"
            document_file.write_text(PROBE_DOCUMENT.replace("Local automated test content.", "Changed text."), encoding="utf-8")
            command("consentgate reload", "Document content changed without a version bump")
            command("consentgate status " + player_id, "test-agreement (probe-1): accepted")
            document_file.write_text(PROBE_DOCUMENT, encoding="utf-8")
            config_file = data_directory / "config.yml"
            config_file.write_text(PROBE_CONFIG.format(fixture=fixture_name).replace("scope: probe", "scope: changed"), encoding="utf-8")
            command("consentgate reload", "require a restart")
            config_file.write_text(PROBE_CONFIG.format(fixture=fixture_name), encoding="utf-8")
            message_file = data_directory / "messages" / "en-US.properties"
            previous_messages = message_file.read_text(encoding="utf-8")
            try:
                message_file.write_text("title=Incomplete\n", encoding="utf-8")
                command("consentgate reload", "Missing interface message")
            finally:
                message_file.write_text(previous_messages, encoding="utf-8")
            command("consentgate reload", "ConsentGate reloaded.")
            command("consentgate reset " + player_id, "History was kept.")
            command("consentgate status " + player_id, "test-agreement (probe-1): acceptance required")
            with closing(sqlite3.connect(database)) as connection:
                decisions = connection.execute(
                    "SELECT decision FROM cg_acceptance_events WHERE player_uuid=? ORDER BY decided_at", (player_id,)
                ).fetchall()
                assert decisions == [("granted",), ("withdrawn",)], decisions
            client = probe_velocity.Client(name, client_id)
            try:
                probe_velocity.no_backend(backend, client, 0.5)
                command("consentgate reset " + player_id, "Disconnect the player first")
                command("consentgate reload", "Reload is busy.")
            finally:
                client.close()
            time.sleep(0.5)
            print("PASS: admin status/reset keeps history, requires consent again, and rejects connected targets", flush=True)
            document_file.write_text(PROBE_DOCUMENT.replace('version: "probe-1"', 'version: "probe-2"'), encoding="utf-8")
            command("consentgate validate", "Validation passed.")
            command("consentgate status " + player_id, "test-agreement (probe-1): acceptance required")
            command("consentgate reload", "ConsentGate reloaded.")
            command("consentgate status " + player_id, "test-agreement (probe-2): acceptance required")
            client = probe_velocity.Client(name, client_id)
            try:
                probe_velocity.no_backend(backend, client, 0.5)
            finally:
                client.close()
            time.sleep(0.5)
            print("PASS: validation is read-only; reload rejects busy/invalid changes and applies a new version", flush=True)

        probe_velocity.main(database, admin_check)
    finally:
        if process.poll() is None:
            try:
                process.stdin.write("shutdown\n")
                process.stdin.flush()
                process.wait(timeout=15)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                process.terminate()
                process.wait(timeout=10)
        process.stdin.close()
        clean_database(database, required=False)
        shutil.rmtree(fixture, ignore_errors=True)


PROBE_CONFIG = """\
config-version: 1
enabled: true
scope: probe
gate:
  timeout-seconds: 300
  max-pending: 1
language:
  default: en-US
  use-client-locale: true
bedrock:
  native-forms: true
appearance:
  title-color: gold
  accent-color: gold
  text-color: white
  muted-color: gray
  error-color: red
  button-color: white
documents:
  directory: {fixture}/documents
storage:
  type: sqlite
  sqlite:
    file: {fixture}/probe.db
"""


PROBE_DOCUMENT = """\
id: test-agreement
version: "probe-1"
required: true
order: 10
translations:
  en-US:
    title: "<gold><bold>Probe Agreement</bold></gold>"
    summary: "<gray>Local automated test content.</gray>"
    checkbox: "I accept the <gold>probe agreement</gold>"
    read-button: "Read the probe agreement"
    pages:
      - title: "First page"
        body: "This content exists only for the <yellow>local automated test</yellow>."
      - title: "Second page"
        body: "Acceptance must be stored before backend admission."
"""


if __name__ == "__main__":
    main()
