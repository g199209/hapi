# Windowless Android commands from WSL

Use this launcher for Windows Android automation. It starts through a GUI-subsystem
executable, so the WSL entry point does not allocate a Windows console. Every child
uses `ProcessStartInfo` with `UseShellExecute=false`, `CreateNoWindow=true` and
redirected stdin/stdout/stderr. Both output pipes drain concurrently to binary logs.
It uses the existing Windows .NET Framework compiler; no SDK/JDK change is needed.

Set the WSL path of Windows `LOCALAPPDATA`, then install once:

```sh
export HAPI_WINDOWS_LOCAL_APPDATA=/mnt/c/Users/YOUR_USER/AppData/Local
python3 android/scripts/windows/android-tools.py install
```

The installer copies the launcher source and compatibility script to
`%LOCALAPPDATA%\HAPI\android-tools` and compiles `HapiHiddenProcess.exe` as
`/target:winexe`. Its one-time compiler bootstrap uses `wscript.exe //B` with a
hidden window. Source/binary hashes are recorded in `installation.json`.
Stop existing launcher runs before reinstalling its executable.

```sh
python3 android/scripts/windows/android-tools.py start-emulator
python3 android/scripts/windows/android-tools.py adb shell getprop sys.boot_completed
python3 android/scripts/windows/android-tools.py adb install -r 'C:\path\app.apk'
python3 android/scripts/windows/android-tools.py adb shell am instrument -w -r \
  run.hapi.companion.test/androidx.test.runner.AndroidJUnitRunner
python3 android/scripts/windows/android-tools.py stop-emulator
```

The existing validation device is `HapiValidation_API_36`, emulator port `5580`,
serial `emulator-5580`, dedicated ADB server `5038`. Default ADB `5037` is untouched.
`start-emulator` returns the launch acknowledgement, PID and log directory;
wait for `sys.boot_completed=1` before using the device. No snapshot load/save or
data wipe is performed. Existing APK, pairing and AVD settings remain available.

Use the same entry point for PowerShell build/sign scripts, individual native
commands and binary output, instead of invoking Windows console executables or
using a foreground PowerShell/TTY fallback:

```sh
python3 android/scripts/windows/android-tools.py run --powershell /mnt/c/path/build.ps1 -BuildKind Debug
python3 android/scripts/windows/android-tools.py adb exec-out screencap -p > screenshot.png
python3 android/scripts/windows/android-tools.py run --background --powershell /mnt/c/path/long-task.ps1
python3 android/scripts/windows/android-tools.py wait /mnt/c/path/to/run-directory
```

`run --stdin-file FILE EXE ...` forwards exact stdin bytes. Arguments use Windows
argument quoting, without shell interpolation. stdout/stderr remain separate;
normal commands return the child's exit code. Full Windows exit codes and launch
errors are retained in `result.json` (codes outside 0–255 map to shell status 1).
Each run under `%LOCALAPPDATA%\HAPI\android-tools\runs` contains `request.json`,
`started.json`, `stdout.log`, `stderr.log` and, after completion, `result.json`.
Ctrl-C stops following logs while the child continues; `wait` reattaches.

For existing per-build `run-emulator.ps1` scripts, delegate to the installed
`%LOCALAPPDATA%\HAPI\android-tools\run-emulator.ps1`. From WSL invoke it through
`run --powershell` above. The compatibility script uses the same GUI launcher and
process flags; it blocks until the emulator exits and keeps its output in run logs.
