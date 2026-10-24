Option Explicit
Dim shell, files, root, compiler, command, status
Set shell = CreateObject("WScript.Shell")
Set files = CreateObject("Scripting.FileSystemObject")
root = files.GetParentFolderName(WScript.ScriptFullName)
compiler = shell.ExpandEnvironmentStrings("%WINDIR%") & "\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
Function Q(value)
    Q = Chr(34) & value & Chr(34)
End Function
' Bootstrap once with the existing .NET Framework compiler, without a console.
command = Q(compiler) & " /nologo /target:winexe /optimize+ /reference:System.Runtime.Serialization.dll /out:" & Q(root & "\HapiHiddenProcess.exe") & " " & Q(root & "\HiddenProcess.cs")
command = Q(shell.ExpandEnvironmentStrings("%COMSPEC%")) & " /d /s /c " & Q(command & " > " & Q(root & "\build-launcher.log") & " 2>&1")
status = shell.Run(command, 0, True)
WScript.Quit status
