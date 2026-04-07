$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location D:\go-analyzer\android
& "C:\Users\xk\AppData\Local\Temp\gradle-8.11.1\bin\gradle.bat" :app:assembleDebug --no-daemon
