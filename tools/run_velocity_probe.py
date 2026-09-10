"""Run the prepared loopback-only proxy and stop it after the wire checks."""

import pathlib
import socket
import subprocess
import sys
import time
import tomllib

import probe_velocity


def main():
    directory = pathlib.Path(__file__).resolve().parents[1] / ".run" / "velocity"
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
        probe_velocity.main()
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


if __name__ == "__main__":
    main()
