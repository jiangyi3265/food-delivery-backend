param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/init-database.ps1"
Set-Location (Split-Path $PSScriptRoot -Parent)
if (!$SkipBuild -or !(Test-Path 'ruoyi-admin/target/ruoyi-admin.jar')) { & mvn -q package -DskipTests; if ($LASTEXITCODE -ne 0) { throw '后端构建失败' } }
& java '-Dfile.encoding=UTF-8' -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=druid,food
if ($LASTEXITCODE -ne 0) { throw '后端启动失败，请先停止旧进程，再执行 mvn package -DskipTests 重新构建' }
