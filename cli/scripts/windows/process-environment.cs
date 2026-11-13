using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

// Read only the current user's desktop process. Values stay local to the node.
public static class HapiProcessEnvironment {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    struct StartupInfo {
        public int cb; public string reserved, desktop, title;
        public int x, y, width, height, charsX, charsY, fill, flags;
        public short showWindow, reservedSize;
        public IntPtr reservedPointer, input, output, error;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct ProcessInfo { public IntPtr process, thread; public int pid, threadId; }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool CreateProcessW(string application, StringBuilder commandLine, IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string cwd, ref StartupInfo startup, out ProcessInfo process);
    [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32.dll")] static extern bool ReadProcessMemory(IntPtr handle, IntPtr address, byte[] bytes, int size, out IntPtr read);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("ntdll.dll")] static extern int NtQueryInformationProcess(IntPtr handle, int type, byte[] bytes, int size, out int length);
    public static int StartDetached(string executable, string arguments, string cwd) {
        var startup = new StartupInfo(); startup.cb = Marshal.SizeOf(startup);
        ProcessInfo process;
        // Framework ProcessStartInfo redirection still inherits unrelated
        // inheritable handles. This GUI helper needs none of the supervisor's
        // handles: its request file specifies the child's streams and logs.
        if (!CreateProcessW(executable, new StringBuilder("\"" + executable + "\" " + arguments), IntPtr.Zero, IntPtr.Zero, false, 0x08000000, IntPtr.Zero, cwd, ref startup, out process))
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        CloseHandle(process.thread); CloseHandle(process.process); return process.pid;
    }
    public static Dictionary<string, string> Read(int pid) {
        var handle = OpenProcess(0x410, false, pid);
        if (handle == IntPtr.Zero) throw new Exception("Cannot read desktop process environment");
        try {
            int length;
            var basic = new byte[48];
            if (NtQueryInformationProcess(handle, 0, basic, basic.Length, out length) != 0) throw new Exception("Cannot query desktop PEB");
            Func<long, int, byte[]> read = (address, size) => {
                var bytes = new byte[size]; IntPtr count;
                if (!ReadProcessMemory(handle, new IntPtr(address), bytes, size, out count) || count.ToInt64() != size) throw new Exception("Cannot read desktop PEB");
                return bytes;
            };
            var peb = BitConverter.ToInt64(basic, 8);
            var parameters = BitConverter.ToInt64(read(peb + 0x20, 8), 0);
            var environment = BitConverter.ToInt64(read(parameters + 0x80, 8), 0);
            var buffer = new List<byte>();
            for (int offset = 0; offset < 1048576; offset += 2) {
                buffer.AddRange(read(environment + offset, 2));
                int n = buffer.Count;
                if (n >= 4 && buffer[n-1] == 0 && buffer[n-2] == 0 && buffer[n-3] == 0 && buffer[n-4] == 0) {
                    var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                    foreach (var line in Encoding.Unicode.GetString(buffer.ToArray()).Split('\0')) {
                        int at = line.IndexOf('=');
                        if (at > 0) result[line.Substring(0, at)] = line.Substring(at+1);
                    }
                    return result;
                }
            }
            throw new Exception("Desktop environment exceeds limit");
        } finally { CloseHandle(handle); }
    }
}
