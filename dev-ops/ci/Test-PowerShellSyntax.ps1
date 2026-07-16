$ErrorActionPreference = 'Stop'

$devOps = Resolve-Path (Join-Path $PSScriptRoot '..')
$files = @(Get-ChildItem -LiteralPath $devOps -Filter '*.ps1' -File -Recurse)
$parseFailures = [System.Collections.Generic.List[string]]::new()

foreach ($file in $files) {
  $tokens = $null
  $errors = $null
  [void][System.Management.Automation.Language.Parser]::ParseFile(
    $file.FullName, [ref]$tokens, [ref]$errors)
  foreach ($error in @($errors)) {
    $parseFailures.Add(
      "$($file.FullName):$($error.Extent.StartLineNumber): $($error.Message)")
  }
}

if ($parseFailures.Count -gt 0) {
  throw ($parseFailures -join [Environment]::NewLine)
}

Write-Host "Parsed $($files.Count) PowerShell release scripts successfully"
