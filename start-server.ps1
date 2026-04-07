$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$python = "C:\Users\xk\.workbuddy\binaries\python\versions\3.13.12\python.exe"
$server = "D:\go-analyzer\server\server.py"
$outFile = "$env:TEMP\go-analyzer-out.log"
$errFile = "$env:TEMP\go-analyzer-err.log"

$p = Start-Process -FilePath $python -ArgumentList $server -NoNewWindow -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $errFile
"SERVER PID: $($p.Id)"
Start-Sleep 5

if ($p.HasExited) {
    "PROCESS EXITED with code: $($p.ExitCode)"
}

@($outFile, $errFile) | ForEach-Object {
    if (Test-Path $_) {
        $content = Get-Content $_ -Raw -Encoding utf8
        if ($content) {
            "=== $((Get-Item $_).Name) ==="
            $content
        }
    }
}
