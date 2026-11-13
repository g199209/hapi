Option Explicit
Dim fs, shell, root, run, request, launcher, command
Set fs = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
root = fs.GetParentFolderName(WScript.ScriptFullName)
If Not fs.FolderExists(root & "\runs") Then fs.CreateFolder(root & "\runs")
Randomize
run = root & "\runs\supervisor-" & Year(Now) & Month(Now) & Day(Now) & "-" & Replace(CStr(Timer), ".", "-") & "-" & CStr(Int(Rnd * 1000000))
fs.CreateFolder(run)
request = run & "\request.json"
fs.CopyFile root & "\supervisor-request.json", request, False
launcher = shell.ExpandEnvironmentStrings("%LOCALAPPDATA%\HAPI\android-tools\HapiHiddenProcess.exe")
command = """" & launcher & """ """ & request & """"
' GUI entry point and GUI launcher: no transient console, including at logon.
WScript.Quit shell.Run(command, 0, True)
