"""Run the prepared loopback-only proxy and stop it after the wire checks."""

import pathlib
import shutil
import socket
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
        probe_velocity.main(database)
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
appearance:
  title-color: gold
  accent-color: yellow
  text-color: white
  muted-color: gray
  error-color: red
  button-color: aqua
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
    checkbox: "I accept the <yellow>probe agreement</yellow>"
    read-button: "<aqua>Read the probe agreement</aqua>"
    pages:
      - title: "First page"
        body: "This content exists only for the <yellow>local automated test</yellow>."
      - title: "Second page"
        body: "Acceptance must be stored before backend admission."
"""


if __name__ == "__main__":
    main()
