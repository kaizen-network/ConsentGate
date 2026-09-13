"""Resolve local plugin artifacts from the fixed Gradle project version."""

import re


def project_version(project):
    match = re.search(r'^\s*version = "([a-zA-Z0-9.-]+)"',
                      (project / 'build.gradle.kts').read_text(encoding='utf-8'), re.MULTILINE)
    if not match:
        raise RuntimeError('Cannot read the fixed project version')
    return match.group(1)


def platform_artifact(project, platform):
    if platform not in ('paper', 'velocity'):
        raise ValueError('Unsupported platform: ' + platform)
    version = project_version(project)
    artifact = project / f'platform-{platform}/build/libs/ConsentGate-{platform.title()}-{version}.jar'
    if not artifact.is_file():
        raise FileNotFoundError('Build the matching ' + platform.title() + ' artifact first: ' + str(artifact))
    return artifact
