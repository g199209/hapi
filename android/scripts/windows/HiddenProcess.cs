using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
using System.Threading.Tasks;

// Build as /target:winexe: the WSL entry point itself must have no console.
[DataContract]
public sealed class Request {
    [DataMember] public string fileName;
    [DataMember] public string arguments;
    [DataMember] public string workingDirectory;
    [DataMember] public Dictionary<string, string> environment;
}

[DataContract]
public sealed class State {
    [DataMember] public int runnerPid = Process.GetCurrentProcess().Id;
    [DataMember] public int processId;
    [DataMember] public string fileName;
    [DataMember] public bool useShellExecute = false;
    [DataMember] public bool createNoWindow = true;
    [DataMember] public string startedAt;
    [DataMember] public string finishedAt;
    [DataMember] public int? exitCode;
    [DataMember] public string error;
}

public static class HiddenProcess {
    private static void WriteState(string path, State state) {
        string temporary = path + ".tmp";
        using (var stream = File.Create(temporary))
            new DataContractJsonSerializer(typeof(State)).WriteObject(stream, state);
        File.Move(temporary, path);
    }

    [STAThread]
    public static int Main(string[] args) {
        if (args.Length != 1) return 2;
        string directory = Path.GetDirectoryName(Path.GetFullPath(args[0]));
        var state = new State();
        try {
            Request request;
            using (var stream = File.OpenRead(args[0])) {
                var serializer = new DataContractJsonSerializer(typeof(Request),
                    new DataContractJsonSerializerSettings { UseSimpleDictionaryFormat = true });
                request = (Request)serializer.ReadObject(stream);
            }
            state.fileName = request.fileName;
            var info = new ProcessStartInfo {
                FileName = request.fileName,
                Arguments = request.arguments ?? "",
                WorkingDirectory = request.workingDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            if (request.environment != null)
                foreach (var pair in request.environment) info.EnvironmentVariables[pair.Key] = pair.Value;
            using (var process = new Process { StartInfo = info })
            using (var stdout = new FileStream(Path.Combine(directory, "stdout.log"), FileMode.Create,
                FileAccess.Write, FileShare.ReadWrite, 1, true))
            using (var stderr = new FileStream(Path.Combine(directory, "stderr.log"), FileMode.Create,
                FileAccess.Write, FileShare.ReadWrite, 1, true)) {
                process.Start();
                state.processId = process.Id;
                state.startedAt = DateTime.UtcNow.ToString("o");
                // Drain both pipes concurrently, including binary ADB output.
                // Descendants may retain them after the immediate child exits.
                Task output = process.StandardOutput.BaseStream.CopyToAsync(stdout);
                Task error = process.StandardError.BaseStream.CopyToAsync(stderr);
                string input = Path.Combine(directory, "stdin.bin");
                Task inputTask = Task.Factory.StartNew(delegate {
                    try {
                        if (File.Exists(input)) using (var stream = File.OpenRead(input))
                            stream.CopyTo(process.StandardInput.BaseStream);
                    } catch (IOException) { /* Child may exit without reading stdin. */ }
                    finally { process.StandardInput.Close(); }
                });
                WriteState(Path.Combine(directory, "started.json"), state);
                process.WaitForExit();
                Task.WaitAll(output, error, inputTask);
                stdout.Flush(); stderr.Flush();
                state.exitCode = process.ExitCode;
            }
        } catch (Exception error) {
            state.error = error.ToString();
            state.exitCode = 1;
        }
        state.finishedAt = DateTime.UtcNow.ToString("o");
        try { WriteState(Path.Combine(directory, "result.json"), state); }
        catch { return 1; }
        return state.exitCode ?? 1;
    }
}
