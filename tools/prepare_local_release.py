"""Build a local test distribution with source materials and checksums. Never uploads."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile


def checksum(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources', type=Path, required=True, help='Local cache for the pinned dependency source files')
    parser.add_argument('--fetch-sources', action='store_true', help='Download missing source files and verify their pinned hashes')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

    def git(*arguments):
        return subprocess.check_output(['git', *arguments], cwd=project, creationflags=flags)

    if git('status', '--porcelain').strip():
        raise RuntimeError('Commit the reviewed changes first so the source archive matches the binaries')
    revision = git('rev-parse', 'HEAD').decode().strip()
    match = re.search(r'^\s*version = "([a-zA-Z0-9.-]+)"', (project / 'build.gradle.kts').read_text(), re.MULTILINE)
    if not match:
        raise RuntimeError('Cannot read the fixed project version')
    version = match.group(1)
    manifest_file = project / 'gradle/dependency-sources.json'
    sources = json.loads(manifest_file.read_text())
    cache = args.sources.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    for entry in sources:
        name = entry['file']
        if Path(name).name != name or not re.fullmatch(r'[a-zA-Z0-9.-]+', name):
            raise ValueError('Invalid source filename')
        path = cache / name
        if not path.is_file() and args.fetch_sources:
            if not entry['url'].startswith('https://'):
                raise ValueError('Source downloads must use HTTPS')
            print('Downloading ' + name, flush=True)
            with urllib.request.urlopen(entry['url'], timeout=60) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValueError('Downloaded source checksum mismatch: ' + name)
            path.write_bytes(data)
        if not path.is_file() or checksum(path) != entry['sha256']:
            raise ValueError('Missing or mismatched source file: ' + str(path))
    print('Dependency source checksums verified', flush=True)
    wrapper = project / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    subprocess.run([str(wrapper), 'build', '--console=plain'], cwd=project, check=True, creationflags=flags)
    if git('status', '--porcelain').strip() or git('rev-parse', 'HEAD').decode().strip() != revision:
        raise RuntimeError('Source changed during the build; create a fresh package after reviewing it')
    reports = list(project.glob('*/build/test-results/test/TEST-*.xml'))
    count = 0
    for report in reports:
        suite = ET.parse(report).getroot()
        if any(int(suite.get(field, '0')) for field in ('failures', 'errors', 'skipped')):
            raise RuntimeError('A required test did not pass: ' + str(report))
        count += int(suite.get('tests', '0'))
    if count == 0:
        raise RuntimeError('No JVM test reports were found')
    root = project / 'build/distributions'
    root.mkdir(parents=True, exist_ok=True)
    name = f'ConsentGate-{version}-{revision[:12]}'
    directory = root / name
    if directory.exists() or (root / (name + '.zip')).exists():
        raise FileExistsError('This revision already has a local package: ' + str(directory))
    staging = root / ('.staging-' + uuid.uuid4().hex)
    staging.mkdir()
    for platform in ('Paper', 'Velocity'):
        filename = f'ConsentGate-{platform}-{version}.jar'
        artifact = project / f'platform-{platform.lower()}/build/libs' / filename
        with zipfile.ZipFile(artifact) as jar:
            required = ['META-INF/LICENSE', 'META-INF/THIRD_PARTY_NOTICES.md',
                        'META-INF/licenses/SnakeYAML.txt', 'META-INF/licenses/MariaDB-Connector-J.txt',
                        'META-INF/licenses/MariaDB-Connector-J-NOTICE.txt',
                        'META-INF/maven/org.xerial/sqlite-jdbc/LICENSE',
                        'META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus']
            if platform == 'Paper':
                required += ['META-INF/licenses/Adventure-NBT.txt', 'META-INF/licenses/Examination.txt']
            for resource in required:
                if not jar.read(resource):
                    raise ValueError('Missing packaged notice: ' + resource)
        shutil.copyfile(artifact, staging / filename)
    for filename in ('LICENSE', 'README.md', 'THIRD_PARTY_NOTICES.md'):
        shutil.copyfile(project / filename, staging / filename)
    shutil.copytree(project / 'docs', staging / 'docs')
    (staging / 'gradle').mkdir()
    shutil.copyfile(manifest_file, staging / 'gradle/dependency-sources.json')
    source_directory = staging / 'dependency-sources'
    source_directory.mkdir()
    for entry in sources:
        shutil.copyfile(cache / entry['file'], source_directory / entry['file'])
    source_zip = staging / f'ConsentGate-{version}-source.zip'
    git('archive', '--format=zip', '--output=' + str(source_zip), revision)
    with zipfile.ZipFile(source_zip) as archive:
        if 'gradle/verification-metadata.xml' not in archive.namelist() or 'tools/prepare_local_release.py' not in archive.namelist():
            raise ValueError('The project source archive is incomplete')
        if any(n.startswith(('.run/', '.env', '.git/')) or '/build/' in n for n in archive.namelist()):
            raise ValueError('Private or generated files must not enter the source archive')
    (staging / 'BUILD.json').write_text(json.dumps({'version': version, 'commit': revision,
        'jvmTests': count, 'status': 'test candidate; final real-client checks remain'}, indent=2) + '\n')
    files = sorted(path for path in staging.rglob('*') if path.is_file())
    (staging / 'SHA256SUMS').write_text(''.join(checksum(path) + '  ' + path.relative_to(staging).as_posix() + '\n' for path in files))
    staging.rename(directory)
    output = root / (name + '.zip')
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(directory.rglob('*')):
            if path.is_file():
                info = zipfile.ZipInfo(name + '/' + path.relative_to(directory).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, path.read_bytes())
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError('Distribution ZIP verification failed')
    output.with_suffix('.zip.sha256').write_text(checksum(output) + '  ' + output.name + '\n')
    print(f'PASS: {count} JVM tests; local package: {output}', flush=True)
    print('No upload, commit, push, or deployment was performed.', flush=True)


if __name__ == '__main__':
    main()
