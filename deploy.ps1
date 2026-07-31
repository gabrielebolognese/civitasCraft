$ErrorActionPreference = "Stop"
.\gradlew.bat build
New-Item -ItemType Directory -Force -Path ..\testserver\plugins | Out-Null
Copy-Item build\libs\CivitasCraft-*.jar ..\testserver\plugins\ -Force
Write-Host "Deployed. Restart the test server."
