"""Failure checks for local runners, using fake processes instead of starting servers."""

from contextlib import ExitStack, contextmanager, redirect_stdout
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from probe_process import stop_server
from build_artifacts import platform_artifact
import run_paper_probe
import run_velocity_probe


class ProbeCleanupTest(unittest.TestCase):
    def setUp(self):
        workspace = Path(__file__).resolve().parents[1]
        run = workspace / '.run'
        run.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='probe-cleanup-test-', dir=run)
        self.project = Path(self.temporary.name).resolve()
        self.assertTrue(self.project.is_relative_to(run.resolve()))
        self.addCleanup(self.temporary.cleanup)
        self.original = b'# original configuration\r\nenabled: false\r\n'
        self.version = '0.2.0-test'
        (self.project / 'build.gradle.kts').write_text('allprojects {\n    version = "' + self.version + '"\n}\n')

    def fixture(self, platform):
        paper = platform == 'paper'
        directory = self.project / '.run' / ('paper-minimal' if paper else 'velocity')
        data = directory / 'plugins' / ('ConsentGate' if paper else 'consentgate')
        data.mkdir(parents=True)
        config = data / 'config.yml'
        config.write_bytes(self.original)
        artifact = self.project / f'platform-{platform}/build/libs/ConsentGate-{platform.title()}-{self.version}.jar'
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b'fixture artifact')
        if paper:
            (directory / 'server.properties').write_text(
                'server-ip=127.0.0.1\nserver-port=25592\nonline-mode=false\nenable-rcon=false\nenable-query=false\n')
            modules = self.project / 'modules'
            (modules / 'minecraft-protocol').mkdir(parents=True)
            (modules / 'prismarine-nbt').mkdir()
            args = ['--modules', str(modules)]
        else:
            (directory / 'velocity.toml').write_text('bind = "127.0.0.1:25590"\nonline-mode = false\n'
                '[advanced]\ncompression-threshold = -1\n[servers]\nbackend = "127.0.0.1:25591"\ntry = ["backend"]\n')
            args = []
        return directory, config, args

    @contextmanager
    def runner(self, platform, args):
        module = run_paper_probe if platform == 'paper' else run_velocity_probe
        with ExitStack() as stack:
            stack.enter_context(patch.object(module, '__file__', str(self.project / 'tools' / f'run_{platform}_probe.py')))
            stack.enter_context(patch.object(sys, 'argv', [f'run_{platform}_probe.py', *args]))
            sockets = stack.enter_context(patch.object(module.socket, 'socket'))
            sockets.return_value.__enter__.return_value.connect_ex.return_value = 1
            process = stack.enter_context(patch.object(module.subprocess, 'Popen'))
            stack.enter_context(redirect_stdout(io.StringIO()))
            yield module, process

    def test_invalid_proxy_binding_does_not_replace_config_or_artifact(self):
        directory, config, args = self.fixture('velocity')
        (directory / 'velocity.toml').write_text('bind = "0.0.0.0:25590"\n')
        with self.runner('velocity', args) as (module, start):
            with self.assertRaisesRegex(AssertionError, 'loopback'):
                module.main()
            start.assert_not_called()
        self.assertEqual(self.original, config.read_bytes())
        self.assertFalse((directory / 'plugins/ConsentGate.jar').exists())
        self.assertFalse((config.parent / 'probe-fixtures').exists())

    def test_process_start_failure_restores_existing_config(self):
        for platform in ('paper', 'velocity'):
            with self.subTest(platform=platform):
                _, config, args = self.fixture(platform)
                with self.runner(platform, args) as (module, start):
                    start.side_effect = OSError('Injected process start failure')
                    with self.assertRaisesRegex(OSError, 'process start failure'):
                        module.main()
                self.assertEqual(self.original, config.read_bytes())

    def test_old_artifact_cannot_substitute_for_current_project_version(self):
        for platform in ('paper', 'velocity'):
            with self.subTest(platform=platform):
                _, config, args = self.fixture(platform)
                artifact = platform_artifact(self.project, platform)
                artifact.rename(artifact.with_name(artifact.name.replace(self.version, '0.1.0-prototype')))
                with self.runner(platform, args) as (module, start):
                    with self.assertRaisesRegex(FileNotFoundError, self.version):
                        module.main()
                    start.assert_not_called()
                self.assertEqual(self.original, config.read_bytes())

    def test_process_start_failure_removes_temporary_config_if_original_was_absent(self):
        for platform in ('paper', 'velocity'):
            with self.subTest(platform=platform):
                _, config, args = self.fixture(platform)
                config.unlink()
                with self.runner(platform, args) as (module, start):
                    start.side_effect = OSError('Injected process start failure')
                    with self.assertRaises(OSError):
                        module.main()
                self.assertFalse(config.exists())

    def test_startup_timeout_stops_owned_server_and_restores_config(self):
        for platform in ('paper', 'velocity'):
            with self.subTest(platform=platform):
                _, config, args = self.fixture(platform)
                with self.runner(platform, args) as (module, start):
                    process = start.return_value
                    process.poll.return_value = None
                    with patch.object(module.time, 'monotonic', side_effect=[0, 100]):
                        with self.assertRaisesRegex(TimeoutError, 'startup exceeded'):
                            module.main()
                    process.stdin.write.assert_called_once_with('stop\n' if platform == 'paper' else 'shutdown\n')
                    process.wait.assert_called_once()
                    process.stdin.close.assert_called_once()
                    expected_flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
                    self.assertEqual(expected_flags, start.call_args.kwargs['creationflags'])
                self.assertEqual(self.original, config.read_bytes())

    def test_shutdown_error_still_restores_config_and_closes_input(self):
        for platform in ('paper', 'velocity'):
            with self.subTest(platform=platform):
                _, config, args = self.fixture(platform)
                with self.runner(platform, args) as (module, start):
                    process = start.return_value
                    process.poll.return_value = None
                    process.wait.side_effect = subprocess.TimeoutExpired('fixture server', 1)
                    process.terminate.side_effect = OSError('Injected shutdown failure')
                    with patch.object(module.time, 'monotonic', side_effect=[0, 100]):
                        with self.assertRaisesRegex(OSError, 'shutdown failure'):
                            module.main()
                    process.terminate.assert_called_once()
                    process.stdin.close.assert_called_once()
                self.assertEqual(self.original, config.read_bytes())

    def test_shutdown_deadlines_escalate_only_for_the_owned_process(self):
        process = MagicMock()
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired('fixture server', 1),
                                   subprocess.TimeoutExpired('fixture server', 1), 0]
        stop_server(process, 'stop', 1)
        process.terminate.assert_called_once()
        process.kill.assert_called_once()
        self.assertEqual(3, process.wait.call_count)
        process.stdin.close.assert_called_once()

    def test_exited_server_is_not_signalled(self):
        process = MagicMock()
        process.poll.return_value = 0
        stop_server(process, 'stop', 1)
        process.stdin.write.assert_not_called()
        process.terminate.assert_not_called()
        process.kill.assert_not_called()
        process.stdin.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()
