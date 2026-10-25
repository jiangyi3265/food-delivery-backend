$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/load-local-config.ps1"
if (!$env:FOOD_DB_USER) { $env:FOOD_DB_USER = 'root' }
if (!$env:FOOD_DB_HOST) { $env:FOOD_DB_HOST = '127.0.0.1' }
if (!$env:FOOD_DB_PORT) { $env:FOOD_DB_PORT = '3306' }
if (!$env:FOOD_DB_NAME) { $env:FOOD_DB_NAME = 'food_delivery41' }
if ($env:FOOD_DB_NAME -notmatch '^food_delivery41[a-zA-Z0-9_]*$') { throw '本地初始化仅允许food_delivery41开头的独立数据库' }
$mysqlArgs = @('--host', $env:FOOD_DB_HOST, '--port', $env:FOOD_DB_PORT, '--user', $env:FOOD_DB_USER)
$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $env:FOOD_DB_PASSWORD
try {
  $dbExists = & mysql @mysqlArgs -N -e "SELECT count(*) FROM information_schema.schemata WHERE schema_name='$env:FOOD_DB_NAME'"
  if ($LASTEXITCODE -ne 0) { throw '数据库连接失败' }
  if ($dbExists.Trim() -eq '0') {
    & mysql @mysqlArgs -e "CREATE DATABASE $env:FOOD_DB_NAME CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
    if ($LASTEXITCODE -ne 0) { throw '创建数据库失败' }
  }
  $tableCount = & mysql @mysqlArgs -N -e "SELECT count(*) FROM information_schema.tables WHERE table_schema='$env:FOOD_DB_NAME'"
  if ($LASTEXITCODE -ne 0) { throw '读取数据库表结构失败' }
  if ($tableCount.Trim() -eq '0') {
    $tempSql = Join-Path ([IO.Path]::GetTempPath()) ('food41-' + [guid]::NewGuid().ToString('N') + '.sql')
    Copy-Item -LiteralPath (Join-Path $taskRoot 'sql/ry_20250522.sql') -Destination $tempSql
    try {
      $baseSql = $tempSql.Replace('\','/')
      & mysql @mysqlArgs --default-character-set=utf8mb4 $env:FOOD_DB_NAME -e "source $baseSql"
      if ($LASTEXITCODE -ne 0) { throw '基础数据初始化失败' }
    } finally { Remove-Item -LiteralPath $tempSql -ErrorAction SilentlyContinue }
    Write-Host "已初始化独立数据库 $env:FOOD_DB_NAME"
  } else { Write-Host "沿用已有数据库 $env:FOOD_DB_NAME" }
} finally { [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousMysqlPassword, 'Process') }
