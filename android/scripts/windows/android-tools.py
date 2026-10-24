#!/usr/bin/env python3
"""WSL entry point for windowless HAPI Android commands; no PowerShell/TTY bootstrap."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

LOCAL_APPDATA = Path(os.environ.get('HAPI_WINDOWS_LOCAL_APPDATA', ''))
TOOLS = LOCAL_APPDATA / 'HAPI/android-tools'
SDK = LOCAL_APPDATA / 'Android/Sdk'
POWERSHELL = Path('/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe')
ANDROID_ENV = {'ANDROID_ADB_SERVER_PORT': '5038', 'ADB_SERVER_SOCKET': 'tcp:localhost:5038'}


def windows(path):
    path = str(path)
    if len(path) > 2 and path[1:3] in (':\\', ':/'):
        return path
    return subprocess.check_output(['wslpath', '-w', str(Path(path).resolve())], text=True).strip()


def launch(executable, arguments, cwd=None, background=False, stdin=None):
    run = TOOLS / 'runs' / (time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:10])
    run.mkdir(parents=True)
    request = {'fileName': windows(executable), 'arguments': subprocess.list2cmdline(arguments),
               'workingDirectory': windows(cwd or TOOLS), 'environment': ANDROID_ENV}
    (run / 'request.json').write_text(json.dumps(request, ensure_ascii=False), encoding='utf-8')
    if stdin is not None:
        (run / 'stdin.bin').write_bytes(stdin)
    runner = TOOLS / 'HapiHiddenProcess.exe'
    if not runner.is_file():
        raise SystemExit('Run android-tools.py install to build HapiHiddenProcess.exe first')
    # GUI-subsystem executable, detached from this tool call; logs carry all I/O.
    subprocess.Popen([str(runner), windows(run / 'request.json')], stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    deadline = time.monotonic() + 20
    while not (run / 'started.json').exists():
        if (run / 'result.json').exists():
            result = json.loads((run / 'result.json').read_text(encoding='utf-8'))
            raise RuntimeError(result.get('error') or 'Child exited before startup')
        if time.monotonic() > deadline:
            raise RuntimeError('No startup acknowledgement; inspect ' + str(run))
        time.sleep(.05)
    if background:
        result = json.loads((run / 'started.json').read_text(encoding='utf-8'))
        result['runDirectory'] = str(run)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        return 0
    return wait(run)


def wait(run):
    readers = [None, None]
    outputs = [sys.stdout.buffer, sys.stderr.buffer]
    try:
        while True:
            finished = (run / 'result.json').exists()
            for i, name in enumerate(('stdout.log', 'stderr.log')):
                if readers[i] is None and (run / name).exists():
                    readers[i] = (run / name).open('rb')
                if readers[i]:
                    data = readers[i].read()
                    if data:
                        outputs[i].write(data)
                        outputs[i].flush()
            if finished:
                result = json.loads((run / 'result.json').read_text(encoding='utf-8'))
                if result.get('error'):
                    print(result['error'], file=sys.stderr)
                return result['exitCode']
            time.sleep(.1)
    except KeyboardInterrupt:
        print('\nCommand remains running; logs: ' + str(run), file=sys.stderr)
        return 130
    finally:
        for reader in readers:
            if reader:
                reader.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('install')
    commands.add_parser('start-emulator')
    commands.add_parser('stop-emulator')
    p = commands.add_parser('adb')
    p.add_argument('arguments', nargs=argparse.REMAINDER)
    p = commands.add_parser('run')
    p.add_argument('--background', action='store_true')
    p.add_argument('--cwd')
    p.add_argument('--stdin-file')
    p.add_argument('--powershell', action='store_true', help='Treat executable as a .ps1 script')
    p.add_argument('executable')
    p.add_argument('arguments', nargs=argparse.REMAINDER)
    p = commands.add_parser('wait')
    p.add_argument('run_directory', type=Path)
    args = parser.parse_args()
    if not os.environ.get('HAPI_WINDOWS_LOCAL_APPDATA'):
        parser.error('Set HAPI_WINDOWS_LOCAL_APPDATA to the WSL path of Windows LOCALAPPDATA')
    if args.command == 'install':
        TOOLS.mkdir(parents=True, exist_ok=True)
        source = Path(__file__).parent
        for name in ['HiddenProcess.cs', 'build-launcher.vbs', 'run-emulator.ps1']:
            shutil.copyfile(source / name, TOOLS / name)
        result = subprocess.run(['/mnt/c/Windows/System32/wscript.exe', '//B', '//Nologo',
                                 windows(TOOLS / 'build-launcher.vbs')])
        if result.returncode:
            raise RuntimeError((TOOLS / 'build-launcher.log').read_text(errors='replace'))
        exe = TOOLS / 'HapiHiddenProcess.exe'
        metadata = {'sourceSHA256': hashlib.sha256((source / 'HiddenProcess.cs').read_bytes()).hexdigest(),
                    'exeSHA256': hashlib.sha256(exe.read_bytes()).hexdigest()}
        (TOOLS / 'installation.json').write_text(json.dumps(metadata, indent=2) + '\n')
        print(json.dumps(metadata))
        return 0
    adb = SDK / 'platform-tools/adb.exe'
    if args.command == 'start-emulator':
        code = launch(adb, ['-P', '5038', 'start-server'])
        if code:
            return code
        return launch(SDK / 'emulator/emulator.exe', ['-avd', 'HapiValidation_API_36', '-port', '5580',
            '-no-snapshot', '-no-boot-anim', '-gpu', 'swiftshader_indirect', '-no-audio', '-no-window'],
            cwd=SDK, background=True)
    if args.command == 'stop-emulator':
        pending = []
        for request_path in (TOOLS / 'runs').glob('*/request.json'):
            request = json.loads(request_path.read_text(encoding='utf-8'))
            if request['fileName'].lower() == windows(SDK / 'emulator/emulator.exe').lower() and not (request_path.parent / 'result.json').exists():
                pending.append(request_path.parent)
        launch(adb, ['-P', '5038', '-s', 'emulator-5580', 'shell', 'sync'])
        code = launch(adb, ['-P', '5038', '-s', 'emulator-5580', 'emu', 'kill'])
        if code:
            return code
        # Emulator shutdown can retain its pipes for twenty seconds. Await
        # completion before stopping ADB or starting another AVD instance.
        deadline = time.monotonic() + 45
        while any(not (run / 'result.json').exists() for run in pending):
            if time.monotonic() > deadline:
                raise RuntimeError('Emulator has not completed shutdown; inspect ' + str(pending))
            time.sleep(.1)
        return launch(adb, ['-P', '5038', 'kill-server'])
    if args.command == 'adb':
        return launch(adb, ['-P', '5038', '-s', 'emulator-5580'] + args.arguments)
    if args.command == 'wait':
        return wait(args.run_directory)
    executable, arguments = args.executable, args.arguments
    if args.powershell:
        executable, arguments = POWERSHELL, ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                                            '-File', windows(args.executable)] + arguments
    return launch(executable, arguments, args.cwd, args.background,
                  Path(args.stdin_file).read_bytes() if args.stdin_file else None)


if __name__ == '__main__':
    code = main()
    sys.exit(code if 0 <= code <= 255 else 1)
