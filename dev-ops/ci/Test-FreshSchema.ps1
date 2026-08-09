$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$schemaPath = (Resolve-Path -LiteralPath (
    Join-Path $repoRoot 'backend/src/main/resources/sql/schema.sql')).Path
$schemaContainerPath = '/docker-entrypoint-initdb.d/01-schema.sql'
$databaseName = 'ai_interview_fresh_schema'
$imageName = 'mysql:8.0'
$labelKey = 'ai-interview.fresh-schema-run'
$runId = "$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$containerName = "ai-interview-fresh-schema-$runId"
$volumeName = "ai-interview-fresh-schema-data-$runId"
$rootPassword = "ci-$([Guid]::NewGuid().ToString('N'))"
$containerCreated = $false
$volumeCreated = $false

function Get-ContainerLogs {
  $previousErrorActionPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $output = @(& docker logs $containerName 2>&1)
  $exitCode = $LASTEXITCODE
  $ErrorActionPreference = $previousErrorActionPreference
  if ($exitCode -ne 0) {
    throw "Could not read logs from fresh-schema container: $containerName"
  }
  return @($output | ForEach-Object { $_.ToString() })
}

function Invoke-MySql {
  param(
    [Parameter(Mandatory)]
    [string] $Sql
  )

  $previousErrorActionPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $output = @(& docker exec --env "MYSQL_PWD=$rootPassword" $containerName `
      mysql --batch --skip-column-names --raw --user=root --database=$databaseName `
      --execute $Sql 2>&1)
  $exitCode = $LASTEXITCODE
  $ErrorActionPreference = $previousErrorActionPreference
  if ($exitCode -ne 0) {
    throw "Fresh-schema query failed: $($output -join [Environment]::NewLine)"
  }
  return @($output | ForEach-Object { $_.ToString() })
}

function Get-OwnedResourceLabels {
  param(
    [Parameter(Mandatory)]
    [ValidateSet('container', 'volume')]
    [string] $ResourceType,

    [Parameter(Mandatory)]
    [string] $ResourceName
  )

  if ($ResourceType -eq 'container') {
    $json = & docker inspect --format '{{json .Config.Labels}}' $ResourceName 2>$null
  } else {
    $json = & docker volume inspect --format '{{json .Labels}}' $ResourceName 2>$null
  }
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) {
    throw "Could not inspect temporary $ResourceType before cleanup: $ResourceName"
  }
  return $json | ConvertFrom-Json
}

& docker version --format '{{.Server.Version}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw 'Docker daemon is unavailable; fresh-schema validation cannot run'
}

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& docker container inspect $containerName 2>$null
$containerInspectExitCode = $LASTEXITCODE
& docker volume inspect $volumeName 2>$null
$volumeInspectExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
if ($containerInspectExitCode -eq 0) {
  throw "Refusing to reuse an existing container: $containerName"
}
if ($volumeInspectExitCode -eq 0) {
  throw "Refusing to reuse an existing volume: $volumeName"
}

$schemaSql = Get-Content -LiteralPath $schemaPath -Raw -Encoding UTF8
$tableMatches = [regex]::Matches(
  $schemaSql, '(?m)^CREATE TABLE IF NOT EXISTS `([^`]+)`')
$declaredTables = @($tableMatches | ForEach-Object { $_.Groups[1].Value })
if ($declaredTables.Count -eq 0) {
  throw 'schema.sql contains no CREATE TABLE declarations'
}
$duplicateDeclarations = @($declaredTables | Group-Object | Where-Object Count -gt 1)
if ($duplicateDeclarations.Count -gt 0) {
  throw "schema.sql declares duplicate tables: $($duplicateDeclarations.Name -join ', ')"
}

$declaredForeignKeyCount = [regex]::Matches(
  $schemaSql, '(?i)\bFOREIGN\s+KEY\s*\(').Count
if ($declaredForeignKeyCount -lt 1) {
  throw 'schema.sql contains no foreign-key constraints'
}

$keyV1Tables = @(
  'capability_content_imports',
  'capability_atom_definitions',
  'capability_templates',
  'job_descriptions',
  'job_interview_preparation_runs',
  'interview_questions',
  'interview_commands',
  'document_tasks',
  'evidence_snapshots',
  'github_repository_bindings',
  'github_code_evidence',
  'algorithm_content_imports',
  'coding_problems',
  'coding_problem_versions',
  'judge_submissions',
  'interview_evidence_reports',
  'capability_profiles',
  'training_tasks',
  'llm_usage_records'
)

$cleanupFailures = [System.Collections.Generic.List[string]]::new()
try {
  $createdVolumeName = & docker volume create --label "$labelKey=$runId" $volumeName
  $volumeCreateExitCode = $LASTEXITCODE
  & docker volume inspect $volumeName *> $null
  $volumeCreated = $LASTEXITCODE -eq 0
  if ($volumeCreateExitCode -ne 0 -or $createdVolumeName.Trim() -ne $volumeName) {
    throw "Could not create fresh-schema volume: $volumeName"
  }

  $containerOutput = @(& docker run --detach --name $containerName `
      --label "$labelKey=$runId" `
      --mount "type=volume,source=$volumeName,target=/var/lib/mysql" `
      --mount "type=bind,source=$schemaPath,target=$schemaContainerPath,readonly" `
      --env "MYSQL_ROOT_PASSWORD=$rootPassword" `
      --env "MYSQL_DATABASE=$databaseName" `
      --env 'MYSQL_INITDB_SKIP_TZINFO=1' `
      $imageName `
      --character-set-server=utf8mb4 `
      --collation-server=utf8mb4_unicode_ci 2>&1)
  $containerCreateExitCode = $LASTEXITCODE
  & docker container inspect $containerName *> $null
  $containerCreated = $LASTEXITCODE -eq 0
  if ($containerCreateExitCode -ne 0) {
    throw "Could not start fresh-schema MySQL: $($containerOutput -join [Environment]::NewLine)"
  }
  Write-Host "Started isolated fresh-schema container: $containerName"

  $deadline = (Get-Date).AddSeconds(180)
  $ready = $false
  while (-not $ready -and (Get-Date) -lt $deadline) {
    $state = & docker inspect --format '{{.State.Status}}' $containerName 2>$null
    if ($LASTEXITCODE -ne 0) {
      throw "Could not inspect fresh-schema container state: $containerName"
    }

    $logs = Get-ContainerLogs
    $schemaErrors = @($logs | Where-Object {
        $_ -match '(?i)(\[ERROR\]|ERROR\s+[0-9]+|FATAL)'
      })
    if ($schemaErrors.Count -gt 0) {
      throw "MySQL schema initialization logged an error: $($schemaErrors -join ' | ')"
    }
    if ($state.Trim() -in @('dead', 'exited')) {
      throw "MySQL exited before fresh-schema validation completed (state=$($state.Trim()))"
    }

    $initializationFinished = ($logs -join "`n") -match
      'MySQL init process done\. Ready for start up\.'
    if ($initializationFinished) {
      & docker exec --env "MYSQL_PWD=$rootPassword" $containerName `
        mysqladmin --user=root ping --silent *> $null
      $ready = $LASTEXITCODE -eq 0
    }
    if (-not $ready) {
      Start-Sleep -Seconds 2
    }
  }
  if (-not $ready) {
    throw 'Timed out after 180 seconds waiting for fresh-schema MySQL initialization'
  }

  $actualTables = @(Invoke-MySql (
      'SELECT TABLE_NAME FROM information_schema.TABLES ' +
      'WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME;'))
  if ($actualTables.Count -ne $declaredTables.Count) {
    throw "Fresh schema table count mismatch: declared=$($declaredTables.Count), " +
      "actual=$($actualTables.Count)"
  }
  $missingTables = @($declaredTables | Where-Object { $_ -notin $actualTables })
  $unexpectedTables = @($actualTables | Where-Object { $_ -notin $declaredTables })
  if ($missingTables.Count -gt 0 -or $unexpectedTables.Count -gt 0) {
    throw "Fresh schema table set mismatch: missing=$($missingTables -join ','), " +
      "unexpected=$($unexpectedTables -join ',')"
  }

  $missingKeyTables = @($keyV1Tables | Where-Object { $_ -notin $actualTables })
  if ($missingKeyTables.Count -gt 0) {
    throw "Fresh schema is missing key V1 tables: $($missingKeyTables -join ', ')"
  }

  # Counts alone do not prove that the V2 trace/idempotency contract was
  # applied correctly.  Verify the changed columns, index uniqueness and FK
  # targets against information_schema before accepting the fresh schema.
  $requiredColumns = @{
    'agent_runs' = @('trace_id', 'root_span_id', 'latency_ms', 'degraded_reason')
    'agent_steps' = @('trace_id', 'span_id', 'parent_span_id', 'status', 'latency_ms', 'metadata_json')
    'rag_query_traces' = @('rag_run_id', 'trace_id')
    'rag_runs' = @('rag_run_id', 'trace_id', 'agent_run_id', 'root_span_id', 'latency_ms', 'degraded_reason')
    'agent_tool_runs' = @('tool_run_id', 'trace_id', 'agent_run_id', 'rag_run_id', 'status', 'cache_hit', 'latency_ms')
    'interview_commands' = @('trace_id', 'agent_run_id', 'expected_session_version')
    'interview_sessions' = @('session_version', 'active_command_id')
    'interview_session_events' = @('source_trace_id')
    'llm_usage_records' = @('trace_id', 'agent_run_id', 'rag_run_id', 'span_id')
    'rag_stage_runs' = @('rag_run_id')
    'rag_retrieval_candidates' = @('rag_run_id')
    'rag_citations' = @('rag_run_id')
    'rag_answer_snapshots' = @('rag_run_id')
  }
  $columnRows = @(Invoke-MySql (
      'SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS ' +
      'WHERE TABLE_SCHEMA = DATABASE();'))
  $columnSet = @{}
  foreach ($row in $columnRows) {
    $parts = $row -split "`t", 2
    if ($parts.Count -eq 2) {
      $columnSet["$($parts[0]).$($parts[1])"] = $true
    }
  }
  $missingColumns = [System.Collections.Generic.List[string]]::new()
  foreach ($table in $requiredColumns.Keys) {
    foreach ($column in $requiredColumns[$table]) {
      if (-not $columnSet.ContainsKey("$table.$column")) {
        $missingColumns.Add("$table.$column")
      }
    }
  }
  if ($missingColumns.Count -gt 0) {
    throw "Fresh schema is missing required columns: $($missingColumns -join ', ')"
  }

  $requiredIndexes = @(
    # INFORMATION_SCHEMA.STATISTICS.NON_UNIQUE is 1 for a normal index and 0 for a unique index.
    @{ Table = 'rag_query_traces'; Name = 'idx_rag_query_traces_trace_created'; Unique = 1; Columns = 'trace_id|created_at' }
    @{ Table = 'rag_query_traces'; Name = 'idx_rag_query_traces_rag_run'; Unique = 1; Columns = 'rag_run_id' }
    @{ Table = 'agent_runs'; Name = 'uk_agent_runs_command_operation'; Unique = 0; Columns = 'session_id|command_id|operation' }
    @{ Table = 'agent_runs'; Name = 'idx_agent_runs_trace'; Unique = 1; Columns = 'trace_id|created_at' }
    @{ Table = 'agent_steps'; Name = 'idx_agent_steps_trace'; Unique = 1; Columns = 'trace_id|created_at' }
    @{ Table = 'rag_runs'; Name = 'uk_rag_run_id'; Unique = 0; Columns = 'rag_run_id' }
    @{ Table = 'rag_runs'; Name = 'idx_rag_run_trace'; Unique = 1; Columns = 'trace_id|created_at' }
    @{ Table = 'agent_tool_runs'; Name = 'idx_agent_tool_trace_time'; Unique = 1; Columns = 'trace_id|started_at' }
    @{ Table = 'interview_commands'; Name = 'idx_interview_command_trace'; Unique = 1; Columns = 'trace_id|created_at' }
    @{ Table = 'interview_session_events'; Name = 'idx_interview_event_trace'; Unique = 1; Columns = 'source_trace_id|created_at' }
    @{ Table = 'llm_usage_records'; Name = 'idx_llm_usage_trace'; Unique = 1; Columns = 'trace_id|created_at' }
  )
  $indexRows = @(Invoke-MySql (
      'SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, ' +
      "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR '|') " +
      'FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() ' +
      'GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE;'))
  $indexSet = @{}
  foreach ($row in $indexRows) {
    $parts = $row -split "`t", 4
    if ($parts.Count -eq 4) {
      $indexSet["$($parts[0]).$($parts[1])"] = @($parts[2], $parts[3])
    }
  }
  $missingIndexes = [System.Collections.Generic.List[string]]::new()
  foreach ($expected in $requiredIndexes) {
    $key = "$($expected.Table).$($expected.Name)"
    if (-not $indexSet.ContainsKey($key)) {
      $missingIndexes.Add($key)
      continue
    }
    $actual = $indexSet[$key]
    if ([int]$actual[0] -ne [int]$expected.Unique -or $actual[1] -ne $expected.Columns) {
      throw "Fresh schema index mismatch: $key expected unique=$($expected.Unique), columns=$($expected.Columns); " +
        "actual unique=$($actual[0]), columns=$($actual[1])"
    }
  }
  if ($missingIndexes.Count -gt 0) {
    throw "Fresh schema is missing required indexes: $($missingIndexes -join ', ')"
  }

  # trace_id must be a normal lookup key: a single trace may own multiple
  # RAG runs.  Reject any unique index whose complete key is only trace_id.
  $uniqueTraceRows = @(Invoke-MySql (
      'SELECT s.TABLE_NAME, s.INDEX_NAME FROM information_schema.STATISTICS s ' +
      'WHERE s.TABLE_SCHEMA = DATABASE() AND s.TABLE_NAME = ''rag_query_traces'' ' +
      'AND s.NON_UNIQUE = 0 GROUP BY s.TABLE_NAME, s.INDEX_NAME ' +
      'HAVING GROUP_CONCAT(s.COLUMN_NAME ORDER BY s.SEQ_IN_INDEX SEPARATOR ''|'') = ''trace_id'';'))
  if ($uniqueTraceRows.Count -gt 0) {
    throw "rag_query_traces.trace_id must not have a unique index: $($uniqueTraceRows -join ', ')"
  }

  $requiredForeignKeys = @(
    @{ Table = 'rag_stage_runs'; Column = 'rag_run_id'; RefTable = 'rag_runs'; RefColumn = 'rag_run_id' }
    @{ Table = 'rag_retrieval_candidates'; Column = 'rag_run_id'; RefTable = 'rag_runs'; RefColumn = 'rag_run_id' }
    @{ Table = 'rag_citations'; Column = 'rag_run_id'; RefTable = 'rag_runs'; RefColumn = 'rag_run_id' }
    @{ Table = 'rag_answer_snapshots'; Column = 'rag_run_id'; RefTable = 'rag_runs'; RefColumn = 'rag_run_id' }
  )
  $foreignKeyRows = @(Invoke-MySql (
      'SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME ' +
      'FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() ' +
      'AND REFERENCED_TABLE_NAME IS NOT NULL;'))
  $foreignKeySet = @{}
  foreach ($row in $foreignKeyRows) {
    $parts = $row -split "`t", 4
    if ($parts.Count -eq 4) {
      $foreignKeySet["$($parts[0]).$($parts[1]).$($parts[2]).$($parts[3])"] = $true
    }
  }
  $missingForeignKeys = [System.Collections.Generic.List[string]]::new()
  foreach ($expected in $requiredForeignKeys) {
    $key = "$($expected.Table).$($expected.Column).$($expected.RefTable).$($expected.RefColumn)"
    if (-not $foreignKeySet.ContainsKey($key)) {
      $missingForeignKeys.Add($key)
    }
  }
  if ($missingForeignKeys.Count -gt 0) {
    throw "Fresh schema is missing required foreign-key targets: $($missingForeignKeys -join ', ')"
  }

  $actualForeignKeyOutput = @(Invoke-MySql (
      'SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS ' +
      'WHERE CONSTRAINT_SCHEMA = DATABASE();'))
  $actualForeignKeyCount = [int]$actualForeignKeyOutput[0]
  if ($actualForeignKeyCount -ne $declaredForeignKeyCount) {
    throw "Fresh schema foreign-key count mismatch: declared=$declaredForeignKeyCount, " +
      "actual=$actualForeignKeyCount"
  }

  $warningOutput = @(Invoke-MySql "SELECT 'warning-probe'; SHOW WARNINGS;")
  $warningRows = @($warningOutput | Select-Object -Skip 1 | Where-Object {
      -not [string]::IsNullOrWhiteSpace($_)
    })
  if ($warningRows.Count -gt 0) {
    throw "SHOW WARNINGS returned rows: $($warningRows -join ' | ')"
  }

  $finalLogs = Get-ContainerLogs
  $schemaErrors = @($finalLogs | Where-Object {
      $_ -match '(?i)(\[ERROR\]|ERROR\s+[0-9]+|FATAL)'
    })
  if ($schemaErrors.Count -gt 0) {
    throw "MySQL logs contain schema ERROR/FATAL: $($schemaErrors -join ' | ')"
  }
  if (($finalLogs -join "`n") -notmatch
      'MySQL init process done\. Ready for start up\.') {
    throw 'MySQL logs do not contain the completed initialization marker'
  }

  Write-Host (
    "Validated fresh MySQL schema: tables=$($actualTables.Count), " +
    "key-v1=$($keyV1Tables.Count), foreign-keys=$actualForeignKeyCount, " +
    'semantic-checks=columns,indexes,unique-constraints,fk-targets, ' +
    'warnings=0, schema-errors=0')
}
finally {
  if ($containerCreated) {
    try {
      $labels = Get-OwnedResourceLabels -ResourceType container -ResourceName $containerName
      $ownerLabel = $labels.PSObject.Properties[$labelKey].Value
      if ($ownerLabel -ne $runId) {
        throw "Temporary container label mismatch; refusing cleanup: $containerName"
      }
      & docker rm --force $containerName | Out-Null
      if ($LASTEXITCODE -ne 0) {
        throw "Could not remove fresh-schema container: $containerName"
      }
      Write-Host "Removed isolated fresh-schema container: $containerName"
    } catch {
      $cleanupFailures.Add($_.Exception.Message)
    }
  }

  if ($volumeCreated) {
    try {
      $labels = Get-OwnedResourceLabels -ResourceType volume -ResourceName $volumeName
      $ownerLabel = $labels.PSObject.Properties[$labelKey].Value
      if ($ownerLabel -ne $runId) {
        throw "Temporary volume label mismatch; refusing cleanup: $volumeName"
      }
      & docker volume rm $volumeName | Out-Null
      if ($LASTEXITCODE -ne 0) {
        throw "Could not remove fresh-schema volume: $volumeName"
      }
      Write-Host "Removed isolated fresh-schema volume: $volumeName"
    } catch {
      $cleanupFailures.Add($_.Exception.Message)
    }
  }

  if ($cleanupFailures.Count -gt 0) {
    throw "Fresh-schema cleanup failed: $($cleanupFailures -join ' | ')"
  }
}
