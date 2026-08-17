$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '../..')
$schemaPath = Join-Path $PSScriptRoot 'capability-content.schema.json'
$catalogRoot = Join-Path $repoRoot 'backend/src/main/resources/capability-content'
$catalogs = @(Get-ChildItem -LiteralPath $catalogRoot -Filter '*.json' -File)

if ($catalogs.Count -eq 0) {
  throw "No versioned capability content was found in $catalogRoot"
}

foreach ($catalogFile in $catalogs) {
  $raw = Get-Content -LiteralPath $catalogFile.FullName -Raw -Encoding UTF8
  if (-not ($raw | Test-Json -SchemaFile $schemaPath)) {
    throw "Content schema validation failed: $($catalogFile.Name)"
  }

  $catalog = $raw | ConvertFrom-Json
  $atomIds = @($catalog.atoms | ForEach-Object atomId)
  if (($atomIds | Sort-Object -Unique).Count -ne $atomIds.Count) {
    throw "Duplicate capability atom id: $($catalogFile.Name)"
  }

  foreach ($template in $catalog.templates) {
    $weight = ($template.capabilities | Measure-Object -Property defaultWeight -Sum).Sum
    if ([Math]::Abs([double]$weight - 1.0) -gt 0.000001) {
      throw "Template weights must sum to 1: $($template.templateCode)"
    }
    foreach ($capability in $template.capabilities) {
      if ($capability.atomId -notin $atomIds) {
        throw "Template references unknown atom: $($capability.atomId)"
      }
    }
  }

  foreach ($question in $catalog.questionTemplates) {
    if ($question.atomId -notin $atomIds) {
      throw "Question references unknown atom: $($question.atomId)"
    }
  }

  foreach ($knowledge in $catalog.platformKnowledge) {
    foreach ($atomId in $knowledge.capabilityAtomIds) {
      if ($atomId -notin $atomIds) {
        throw "Platform knowledge references unknown atom: $atomId"
      }
    }
  }

  Write-Host "Validated content catalog $($catalogFile.Name): $($atomIds.Count) atoms"
}
