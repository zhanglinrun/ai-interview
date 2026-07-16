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

$algorithmPath = Join-Path $repoRoot `
  'backend/src/main/resources/algorithm-content/hot100-v1.json'
if (-not (Test-Path -LiteralPath $algorithmPath -PathType Leaf)) {
  throw "Algorithm catalog was not found: $algorithmPath"
}

$algorithm = Get-Content -LiteralPath $algorithmPath -Raw -Encoding UTF8 | ConvertFrom-Json
$problems = @($algorithm.problems)
$enabledProblems = @($algorithm.enabledProblems)
if ($algorithm.schemaVersion -ne '1.0.0') {
  throw "Unsupported algorithm schemaVersion: $($algorithm.schemaVersion)"
}
if ([string]::IsNullOrWhiteSpace($algorithm.contentVersion)) {
  throw 'Algorithm contentVersion is required'
}
if ($algorithm.checksum -notmatch '^sha256:[a-f0-9]{64}$') {
  throw 'Algorithm checksum must be a sha256 digest'
}
if ($problems.Count -ne 100) {
  throw "Hot 100 mapping must contain exactly 100 problems; found $($problems.Count)"
}
if ($enabledProblems.Count -ne 20) {
  throw "V1 must contain exactly 20 enabled problems; found $($enabledProblems.Count)"
}

$platformIds = @($problems | ForEach-Object platformProblemId)
$ranks = @($problems | ForEach-Object hotRank)
$slugs = @($problems | ForEach-Object slug)
$uniquePlatformIdCount = @($platformIds | Sort-Object -Unique).Count
$uniqueRankCount = @($ranks | Sort-Object -Unique).Count
$uniqueSlugCount = @($slugs | Sort-Object -Unique).Count
if ($uniquePlatformIdCount -ne 100 -or
    $uniqueRankCount -ne 100 -or
    $uniqueSlugCount -ne 100) {
  throw 'Hot 100 platformProblemId, hotRank and slug must be unique'
}
if (@($ranks | Where-Object { $_ -lt 1 -or $_ -gt 100 }).Count -gt 0) {
  throw 'Hot 100 ranks must be within 1..100'
}

$enabledIds = @($enabledProblems | ForEach-Object platformProblemId)
if (($enabledIds | Sort-Object -Unique).Count -ne 20) {
  throw 'Enabled problem ids must be unique'
}
foreach ($enabled in $enabledProblems) {
  if ($enabled.platformProblemId -notin $platformIds) {
    throw "Enabled problem is absent from Hot 100 mapping: $($enabled.platformProblemId)"
  }
  $version = $enabled.version
  if (-not $version.enabled -or [string]::IsNullOrWhiteSpace($version.statement)) {
    throw "Enabled problem has no active self-authored statement: $($enabled.platformProblemId)"
  }
  $languages = @($version.languages)
  $languageNames = @($languages | ForEach-Object language | Sort-Object)
  if ($languages.Count -ne 2 -or ($languageNames -join ',') -ne 'JAVA21,PYTHON3') {
    throw "Enabled problem must provide exactly Java 21 and Python 3: $($enabled.platformProblemId)"
  }
  foreach ($language in $languages) {
    $languageInvalid = -not $language.enabled -or
      [string]::IsNullOrWhiteSpace($language.functionName) -or
      [string]::IsNullOrWhiteSpace($language.template) -or
      [string]::IsNullOrWhiteSpace($language.referenceSolution) -or
      -not $language.template.Contains('class Solution') -or
      -not $language.template.Contains($language.functionName) -or
      -not $language.referenceSolution.Contains('class Solution') -or
      -not $language.referenceSolution.Contains($language.functionName) -or
      $language.referenceSolution.Trim() -eq $language.template.Trim()
    if ($languageInvalid) {
      throw "Invalid language template/reference pair: $($enabled.platformProblemId)/$($language.language)"
    }
  }
  $publicTests = @($version.publicTests)
  $hiddenTests = @($version.hiddenTests)
  if ($publicTests.Count -lt 2 -or $hiddenTests.Count -lt 3) {
    throw "Enabled problem requires >=2 public and >=3 hidden tests: $($enabled.platformProblemId)"
  }
  $testIds = @($publicTests + $hiddenTests | ForEach-Object id)
  if (($testIds | Sort-Object -Unique).Count -ne $testIds.Count) {
    throw "Test ids must be unique within a problem: $($enabled.platformProblemId)"
  }
}

Write-Host "Validated algorithm catalog hot100-v1.json: 100 mapped, 20 bilingual runnable"
