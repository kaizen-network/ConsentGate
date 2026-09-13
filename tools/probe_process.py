"""Shutdown for server processes owned by the local probe runners."""

import subprocess


def stop_server(process, command, timeout):
    if process is None:
        return
    try:
        if process.poll() is None:
            try:
                process.stdin.write(command + '\n')
                process.stdin.flush()
                process.wait(timeout=timeout)
            except (OSError, subprocess.TimeoutExpired):
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
    finally:
        process.stdin.close()
