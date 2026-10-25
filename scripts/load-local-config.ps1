$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$localConfig = Join-Path $taskRoot 'config/local.json'
if (Test-Path -LiteralPath $localConfig) {
  $settings = Get-Content -LiteralPath $localConfig -Raw | ConvertFrom-Json
  foreach ($property in $settings.PSObject.Properties) {
    if ($property.Name -notmatch '^(FOOD_|RUOYI_|DRUID_)[A-Z0-9_]+$') {
      throw "不支持的本地配置项：$($property.Name)"
    }
    if ($null -eq [Environment]::GetEnvironmentVariable($property.Name, 'Process')) {
      [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
    }
  }
}
