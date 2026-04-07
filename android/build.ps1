$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_USER_HOME = "D:\.gradle"
$env:ANDROID_HOME = "C:\Users\xk\AppData\Local\Android\Sdk"

$gradleBat = "C:\Users\xk\AppData\Local\Temp\gradle-8.11.1\bin\gradle.bat"

Write-Host "Starting Gradle build..."
& $gradleBat assembleDebug --no-daemon
