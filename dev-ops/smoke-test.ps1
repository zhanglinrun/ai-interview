param(
  [string]$BaseUrl = 'http://127.0.0.1:8080',
  [string]$FilesBaseUrl = '',
  [switch]$RequireFullFlow,
  [switch]$SkipCleanup,
  [string]$ReportPath = '',
  [int]$PollIntervalSeconds = 2,
  [int]$ExternalTimeoutSeconds = 420
)

$ErrorActionPreference = 'Stop'

$results = [System.Collections.Generic.List[object]]::new()
$base = $BaseUrl.TrimEnd('/')
$token = $null
$jobTargetId = $null
$githubRepositoryId = $null
$knowledgeBaseId = $null
$byokConfigured = $false

function Protect-Evidence {
  param([string]$Value)

  if ([string]::IsNullOrWhiteSpace($Value)) {
    return 'no detail'
  }
  $safe = $Value -replace '(?i)(api[_-]?key|authorization|password|secret|token)(\s*[:=]\s*)[^,;\s]+', '$1$2[REDACTED]'
  $safe = $safe -replace '(https?://[^?\s]+)\?[^\s]+', '$1?[REDACTED]'
  return $safe.Substring(0, [Math]::Min(300, $safe.Length))
}

function Add-Result {
  param(
    [string]$Stage,
    [ValidateSet('PASS', 'FAIL', 'UNVERIFIED', 'SKIPPED', 'WARN')]
    [string]$Status,
    [string]$Evidence
  )

  $results.Add([pscustomobject]@{
    stage = $Stage
    status = $Status
    evidence = Protect-Evidence $Evidence
  })
}

function Get-SmokeEnvironment {
  param([string]$Name, [string]$Default = '')

  $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
  if ([string]::IsNullOrWhiteSpace($value)) {
    return $Default
  }
  return $value.Trim()
}

function Invoke-HttpProbe {
  param(
    [string]$Stage,
    [string]$Method,
    [string]$Uri,
    [int[]]$ExpectedStatus
  )

  try {
    $response = Invoke-WebRequest -Method $Method -Uri $Uri -SkipHttpErrorCheck `
      -MaximumRedirection 0 -TimeoutSec 15
    if ($response.StatusCode -notin $ExpectedStatus) {
      throw "unexpected HTTP $($response.StatusCode)"
    }
    Add-Result -Stage $Stage -Status 'PASS' -Evidence "HTTP $($response.StatusCode)"
    return $true
  } catch {
    Add-Result -Stage $Stage -Status 'FAIL' -Evidence $_.Exception.Message
    return $false
  }
}

function Invoke-Api {
  param(
    [Parameter(Mandatory)] [string]$Method,
    [Parameter(Mandatory)] [string]$Path,
    [object]$Body = $null,
    [string]$AccessToken = ''
  )

  $headers = @{}
  if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
    $headers.Authorization = "Bearer $AccessToken"
  }
  $parameters = @{
    Method = $Method
    Uri = "$base$Path"
    Headers = $headers
    SkipHttpErrorCheck = $true
    MaximumRedirection = 0
    TimeoutSec = $ExternalTimeoutSeconds
  }
  if ($null -ne $Body) {
    $parameters.ContentType = 'application/json; charset=utf-8'
    $parameters.Body = $Body | ConvertTo-Json -Depth 30 -Compress
  }
  $response = Invoke-WebRequest @parameters
  if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
    throw "HTTP $($response.StatusCode) from $Path"
  }
  $payload = $response.Content | ConvertFrom-Json
  if ($payload.code -ne 200) {
    throw "API code $($payload.code) from $Path"
  }
  return $payload.data
}

function Invoke-MultipartApi {
  param(
    [Parameter(Mandatory)] [string]$Path,
    [Parameter(Mandatory)] [string]$FilePath,
    [Parameter(Mandatory)] [string]$AccessToken
  )

  $file = Get-Item -LiteralPath $FilePath
  $response = Invoke-WebRequest -Method Post -Uri "$base$Path" `
    -Headers @{ Authorization = "Bearer $AccessToken" } `
    -Form @{
      file = $file
      name = "release-smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
      category = 'release-smoke'
      accessibleBy = 'PRIVATE'
    } `
    -SkipHttpErrorCheck -MaximumRedirection 0 -TimeoutSec $ExternalTimeoutSeconds
  if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
    throw "HTTP $($response.StatusCode) from $Path"
  }
  $payload = $response.Content | ConvertFrom-Json
  if ($payload.code -ne 200) {
    throw "API code $($payload.code) from $Path"
  }
  return $payload.data
}

function Test-SseReplay {
  param(
    [Parameter(Mandatory)] [string]$Path,
    [Parameter(Mandatory)] [string]$AccessToken
  )

  $handler = [System.Net.Http.HttpClientHandler]::new()
  $handler.AllowAutoRedirect = $false
  $client = [System.Net.Http.HttpClient]::new($handler)
  $request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Get, "$base$Path")
  $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
    'Bearer', $AccessToken)
  $cancellation = [System.Threading.CancellationTokenSource]::new(
    [TimeSpan]::FromSeconds(15))
  try {
    $response = $client.SendAsync(
      $request,
      [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
      $cancellation.Token).GetAwaiter().GetResult()
    if ([int]$response.StatusCode -ne 200) {
      throw "SSE returned HTTP $([int]$response.StatusCode)"
    }
    $contentType = $response.Content.Headers.ContentType.MediaType
    if ($contentType -ne 'text/event-stream') {
      throw "SSE returned content type $contentType"
    }
    return $contentType
  } finally {
    if ($null -ne $response) {
      $response.Dispose()
    }
    $cancellation.Dispose()
    $request.Dispose()
    $client.Dispose()
    $handler.Dispose()
  }
}

function Wait-Preparation {
  param([string]$RunId, [string]$AccessToken)

  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ExternalTimeoutSeconds)
  do {
    $view = Invoke-Api -Method Get -Path "/api/job-interviews/preparations/$RunId" `
      -AccessToken $AccessToken
    if ($view.status -in @('READY', 'FAILED')) {
      return $view
    }
    Start-Sleep -Seconds $PollIntervalSeconds
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw 'preparation polling timed out'
}

function Wait-Report {
  param([string]$SessionId, [string]$AccessToken)

  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ExternalTimeoutSeconds)
  do {
    $view = Invoke-Api -Method Get -Path "/api/reports/sessions/$SessionId" `
      -AccessToken $AccessToken
    if ($view.status -in @('COMPLETED', 'FAILED')) {
      return $view
    }
    Start-Sleep -Seconds $PollIntervalSeconds
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw 'report polling timed out'
}

$edgeHealthy = Invoke-HttpProbe -Stage 'edge-health' -Method Get `
  -Uri "$base/healthz" -ExpectedStatus @(200)

if ($FilesBaseUrl) {
  $files = $FilesBaseUrl.TrimEnd('/')
  $writeBlocked = Invoke-HttpProbe -Stage 'files-public-write-blocked' -Method Put `
    -Uri "$files/smoke-write-must-fail" -ExpectedStatus @(405)
  $unsignedBlocked = Invoke-HttpProbe -Stage 'files-unsigned-read-blocked' -Method Get `
    -Uri "$files/ai-interview/smoke-unsigned-object" -ExpectedStatus @(400, 403, 404)
  if ($writeBlocked -and $unsignedBlocked) {
    Add-Result -Stage 'private-files-domain' -Status 'PASS' `
      -Evidence 'write denied and unsigned read unavailable'
  } else {
    Add-Result -Stage 'private-files-domain' -Status 'FAIL' `
      -Evidence 'one or more private file boundary probes failed'
  }
} elseif ($RequireFullFlow) {
  Add-Result -Stage 'private-files-domain' -Status 'UNVERIFIED' `
    -Evidence 'FilesBaseUrl is required for full release smoke'
} else {
  Add-Result -Stage 'private-files-domain' -Status 'SKIPPED' `
    -Evidence 'FilesBaseUrl was not supplied'
}

$businessStages = @(
  'register-login',
  'byok-capability-check',
  'mineru-rag-document',
  'jd-create-analyze-freeze',
  'github-fixed-sha-evidence',
  'interview-rest-sse',
  'judge0-objective-result',
  'report-profile-training'
)

if (-not $RequireFullFlow) {
  foreach ($stage in $businessStages) {
    Add-Result -Stage $stage -Status 'SKIPPED' `
      -Evidence 'Use -RequireFullFlow with explicit SMOKE_* inputs to execute business E2E'
  }
} elseif (-not $edgeHealthy) {
  foreach ($stage in $businessStages) {
    Add-Result -Stage $stage -Status 'SKIPPED' -Evidence 'edge health failed'
  }
} else {
  $jobMappings = @()
  $githubReady = $false
  $jobReady = $false
  $documentReady = $false
  $sessionCompleted = $false
  $sessionId = $null

  try {
    $suffix = ([guid]::NewGuid().ToString('N')).Substring(0, 12)
    $username = "smoke-$suffix"
    $password = "Sm0ke!-$([guid]::NewGuid().ToString('N'))"
    $auth = Invoke-Api -Method Post -Path '/api/auth/register' -Body @{
      username = $username
      email = "$username@example.com"
      password = $password
      displayName = 'Release Smoke'
    }
    $token = $auth.accessToken
    if ([string]::IsNullOrWhiteSpace($token)) {
      throw 'registration returned no access token'
    }
    $login = Invoke-Api -Method Post -Path '/api/auth/login' -Body @{
      username = $username
      password = $password
    }
    if ([string]::IsNullOrWhiteSpace($login.accessToken)) {
      throw 'login returned no access token'
    }
    $token = $login.accessToken
    Add-Result -Stage 'register-login' -Status 'PASS' -Evidence "userId=$($auth.userId)"
  } catch {
    Add-Result -Stage 'register-login' -Status 'FAIL' -Evidence $_.Exception.Message
  }

  $byokKey = Get-SmokeEnvironment 'SMOKE_BYOK_API_KEY'
  $byokBaseUrl = Get-SmokeEnvironment 'SMOKE_BYOK_BASE_URL' `
    'https://dashscope.aliyuncs.com/compatible-mode/v1'
  $byokModel = Get-SmokeEnvironment 'SMOKE_BYOK_MODEL' 'qwen3.5-flash'
  if (-not $token) {
    Add-Result -Stage 'byok-capability-check' -Status 'SKIPPED' `
      -Evidence 'registration did not complete'
  } elseif ([string]::IsNullOrWhiteSpace($byokKey)) {
    Add-Result -Stage 'byok-capability-check' -Status 'UNVERIFIED' `
      -Evidence 'SMOKE_BYOK_API_KEY was not supplied to the process'
  } else {
    try {
      Invoke-Api -Method Put -Path '/api/llm-provider/mine' -AccessToken $token -Body @{
        baseUrl = $byokBaseUrl
        apiKey = $byokKey
        chatModel = $byokModel
        temperature = 0.2
      } | Out-Null
      $providerTest = Invoke-Api -Method Post -Path '/api/llm-provider/mine/test' `
        -AccessToken $token
      if (-not $providerTest.success) {
        throw 'BYOK provider connectivity check returned unsuccessful'
      }
      $byokConfigured = $true
      Add-Result -Stage 'byok-capability-check' -Status 'PASS' `
        -Evidence "model=$($providerTest.model)"
    } catch {
      Add-Result -Stage 'byok-capability-check' -Status 'FAIL' -Evidence $_.Exception.Message
    }
  }

  $documentPath = Get-SmokeEnvironment 'SMOKE_DOCUMENT_PATH'
  if (-not $token) {
    Add-Result -Stage 'mineru-rag-document' -Status 'SKIPPED' `
      -Evidence 'registration did not complete'
  } elseif ([string]::IsNullOrWhiteSpace($documentPath)) {
    Add-Result -Stage 'mineru-rag-document' -Status 'UNVERIFIED' `
      -Evidence 'SMOKE_DOCUMENT_PATH was not supplied; use a non-sensitive PDF fixture'
  } else {
    try {
      if (-not (Test-Path -LiteralPath $documentPath -PathType Leaf)) {
        throw 'SMOKE_DOCUMENT_PATH does not point to a file'
      }
      if ([IO.Path]::GetExtension($documentPath) -ne '.pdf') {
        throw 'SMOKE_DOCUMENT_PATH must be a PDF to exercise MinerU'
      }
      $upload = Invoke-MultipartApi -Path '/api/knowledgebase/upload' `
        -FilePath $documentPath -AccessToken $token
      $knowledgeBaseId = [long]$upload.knowledgeBase.id
      $versions = @(Invoke-Api -Method Get `
          -Path "/api/knowledgebase/$knowledgeBaseId/versions" -AccessToken $token)
      if ($versions.Count -eq 0) {
        throw 'uploaded document returned no version'
      }
      $versionId = [long]$versions[0].versionId
      $parseTask = Invoke-Api -Method Get `
        -Path "/api/knowledgebase/$knowledgeBaseId/versions/$versionId/parse-task" `
        -AccessToken $token
      $split = Invoke-Api -Method Post -Path "/api/knowledgebase/$knowledgeBaseId/split" `
        -AccessToken $token -Body @{}
      $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ExternalTimeoutSeconds)
      do {
        $document = Invoke-Api -Method Get -Path "/api/knowledgebase/$knowledgeBaseId" `
          -AccessToken $token
        if ($document.docStatus -eq 'VECTOR_STORED') {
          break
        }
        Start-Sleep -Seconds $PollIntervalSeconds
      } while ([DateTimeOffset]::UtcNow -lt $deadline)
      if ($document.docStatus -ne 'VECTOR_STORED') {
        throw "document vectorization ended at $($document.docStatus)"
      }
      if ($byokConfigured) {
        $rag = Invoke-Api -Method Post -Path '/api/knowledgebase/query' `
          -AccessToken $token -Body @{
            knowledgeBaseIds = @($knowledgeBaseId)
            question = Get-SmokeEnvironment 'SMOKE_DOCUMENT_QUESTION' `
              '请概括该文档的核心主题，并给出引用。'
          }
        if ([string]::IsNullOrWhiteSpace($rag.answer) -or @($rag.sources).Count -eq 0) {
          throw 'RAG query returned no answer or source'
        }
      }
      $documentReady = $true
      if ($parseTask.status -eq 'SUCCEEDED') {
        Add-Result -Stage 'mineru-rag-document' -Status 'PASS' `
          -Evidence "documentId=$knowledgeBaseId; segments=$($split.segmentCount); parser=MinerU"
      } else {
        Add-Result -Stage 'mineru-rag-document' -Status 'UNVERIFIED' `
          -Evidence "documentId=$knowledgeBaseId; parserStatus=$($parseTask.status)"
      }
    } catch {
      Add-Result -Stage 'mineru-rag-document' -Status 'FAIL' -Evidence $_.Exception.Message
    }
  }

  if (-not $token -or -not $byokConfigured) {
    Add-Result -Stage 'jd-create-analyze-freeze' -Status 'SKIPPED' `
      -Evidence 'registration and a verified BYOK provider are required'
  } else {
    try {
      $jdText = @'
负责 Java 后端与 AI 应用研发，要求熟悉 Java 21、Spring Boot、MySQL、Redis、
Elasticsearch 和 RabbitMQ；能够设计 RAG 文档处理、混合检索、Rerank、引用校验与评测链路；
理解 Agent 工具调用、状态机、幂等、事务边界、故障降级与可观测性；能够结合真实代码说明设计取舍。
'@
      $job = Invoke-Api -Method Post -Path '/api/job-targets' -AccessToken $token -Body @{
        title = 'Release Smoke Java/RAG Engineer'
        company = 'Smoke Fixture'
        jobTrack = 'AI_RAG_AGENT'
        jdText = $jdText
        sourceUrl = $null
      }
      $jobTargetId = [long]$job.id
      $analysis = Invoke-Api -Method Post -Path "/api/job-targets/$jobTargetId/analyze" `
        -AccessToken $token
      $jobMappings = @($analysis.capabilities)
      if ($jobMappings.Count -eq 0) {
        throw 'JD analysis returned no capability mapping'
      }
      $adjustments = @($jobMappings | ForEach-Object {
          @{
            mappingId = [long]$_.id
            enabled = $true
            weight = if ($null -ne $_.suggestedWeight) { $_.suggestedWeight } else { 0.1 }
          }
        })
      Invoke-Api -Method Put -Path "/api/job-targets/$jobTargetId/capabilities" `
        -AccessToken $token -Body @{
          adjustments = $adjustments
          temporaryCapability = $null
        } | Out-Null
      $frozen = Invoke-Api -Method Post -Path "/api/job-targets/$jobTargetId/freeze" `
        -AccessToken $token
      if ($frozen.status -ne 'FROZEN') {
        throw "JD freeze ended at $($frozen.status)"
      }
      $jobReady = $true
      Add-Result -Stage 'jd-create-analyze-freeze' -Status 'PASS' `
        -Evidence "jobTargetId=$jobTargetId; mappings=$($jobMappings.Count); fallback=$($analysis.fallbackUsed)"
    } catch {
      Add-Result -Stage 'jd-create-analyze-freeze' -Status 'FAIL' -Evidence $_.Exception.Message
    }
  }

  $githubUrl = Get-SmokeEnvironment 'SMOKE_GITHUB_REPOSITORY_URL'
  if (-not $token) {
    Add-Result -Stage 'github-fixed-sha-evidence' -Status 'SKIPPED' `
      -Evidence 'registration did not complete'
  } elseif ([string]::IsNullOrWhiteSpace($githubUrl)) {
    Add-Result -Stage 'github-fixed-sha-evidence' -Status 'UNVERIFIED' `
      -Evidence 'SMOKE_GITHUB_REPOSITORY_URL was not supplied'
  } elseif ($jobMappings.Count -eq 0) {
    Add-Result -Stage 'github-fixed-sha-evidence' -Status 'SKIPPED' `
      -Evidence 'JD capability mappings are required for evidence cards'
  } else {
    try {
      $bound = Invoke-Api -Method Post -Path '/api/github/repositories' `
        -AccessToken $token -Body @{
          repositoryUrl = $githubUrl
          contribution = @{
            coreModules = @('backend')
            responsibilities = 'Release smoke fixture: verify read-only fixed-SHA evidence.'
            keyDecisions = 'Use immutable source evidence and explicit degradation.'
            problemsSolved = 'Validate repository manifest, sync and evidence-card contracts.'
          }
        }
      $githubRepositoryId = [long]$bound.repository.id
      $commitSha = [string]$bound.repository.fixedCommitSha
      $sync = Invoke-Api -Method Post `
        -Path "/api/github/repositories/$githubRepositoryId/sync" `
        -AccessToken $token -Body @{
          expectedCommitSha = $commitSha
          includePaths = @()
          excludePrefixes = @('.git', 'node_modules', 'target', 'dist')
        }
      if ($sync.status -notin @('SYNCED', 'PARTIAL') -or $sync.evidenceChunks -le 0) {
        throw "GitHub sync ended at $($sync.status)"
      }
      $targets = @($jobMappings | Select-Object -First 3 | ForEach-Object {
          @{
            atomId = $_.atomId
            atomVersion = $_.atomVersion
            name = $_.capabilityName
            keywords = @()
          }
        })
      $cards = @(Invoke-Api -Method Post `
          -Path "/api/github/repositories/$githubRepositoryId/evidence-cards" `
          -AccessToken $token -Body @{
            capabilities = $targets
            evidencePerCapability = 2
          })
      if ($cards.Count -eq 0) {
        throw 'GitHub evidence-card generation returned no card'
      }
      if (@($cards | Where-Object evidenceStatus -ne 'NONE').Count -eq 0) {
        throw 'GitHub evidence cards contain no matched fixed-SHA evidence'
      }
      $githubReady = $true
      Add-Result -Stage 'github-fixed-sha-evidence' -Status 'PASS' `
        -Evidence "repositoryId=$githubRepositoryId; sha=$($commitSha.Substring(0, 8)); files=$($sync.syncedFiles); cards=$($cards.Count)"
    } catch {
      Add-Result -Stage 'github-fixed-sha-evidence' -Status 'FAIL' -Evidence $_.Exception.Message
    }
  }

  if (-not $token -or -not $jobReady) {
    Add-Result -Stage 'interview-rest-sse' -Status 'SKIPPED' `
      -Evidence 'a frozen JD is required'
  } else {
    try {
      $knowledgeIds = if ($documentReady) { @($knowledgeBaseId) } else { @() }
      $preparation = Invoke-Api -Method Post -Path '/api/job-interviews/preparations' `
        -AccessToken $token -Body @{
          jobDescriptionId = $jobTargetId
          resumeId = $null
          githubRepositoryId = if ($githubReady) { $githubRepositoryId } else { $null }
          knowledgeBaseIds = $knowledgeIds
          includePersonalMaterials = $documentReady
          codingLanguage = 'JAVA21'
          regenerate = $true
        }
      $preparation = Wait-Preparation -RunId $preparation.runId -AccessToken $token
      if ($preparation.status -ne 'READY') {
        throw "preparation ended at $($preparation.status)"
      }
      $sessionId = [string]$preparation.sessionId
      $start = Invoke-Api -Method Post `
        -Path "/api/job-interviews/sessions/$sessionId/start" `
        -AccessToken $token -Body @{
          commandId = "start-$([guid]::NewGuid().ToString('N'))"
          expectedSessionVersion = [long]$preparation.sessionVersion
        }
      $cursor = [Math]::Max(0L, [long]$start.eventId - 1L)
      $sseType = Test-SseReplay `
        -Path "/api/job-interviews/sessions/$sessionId/events?afterEventId=$cursor" `
        -AccessToken $token
      $question = $start.currentQuestion
      if ($null -eq $question) {
        throw 'started interview returned no current question'
      }
      if ($question.stage -eq 'ALGORITHM') {
        $answerResult = Invoke-Api -Method Post `
          -Path "/api/job-interviews/sessions/$sessionId/code/submit" `
          -AccessToken $token -Body @{
            commandId = "code-$([guid]::NewGuid().ToString('N'))"
            expectedSessionVersion = [long]$start.sessionVersion
            questionId = [long]$question.questionId
            sourceCode = 'class Solution {}'
          }
      } else {
        $answerResult = Invoke-Api -Method Post `
          -Path "/api/job-interviews/sessions/$sessionId/answers" `
          -AccessToken $token -Body @{
            commandId = "answer-$([guid]::NewGuid().ToString('N'))"
            expectedSessionVersion = [long]$start.sessionVersion
            questionId = [long]$question.questionId
            answer = '我会先明确约束和证据边界，再给出实现主链路、异常降级、验证方式与可替代方案。'
          }
      }
      if ($answerResult.sessionStatus -ne 'COMPLETED') {
        $finished = Invoke-Api -Method Post `
          -Path "/api/job-interviews/sessions/$sessionId/finish" `
          -AccessToken $token -Body @{
            commandId = "finish-$([guid]::NewGuid().ToString('N'))"
            expectedSessionVersion = [long]$answerResult.sessionVersion
          }
      } else {
        $finished = $answerResult
      }
      if ($finished.sessionStatus -ne 'COMPLETED') {
        throw "interview finish ended at $($finished.sessionStatus)"
      }
      $sessionCompleted = $true
      Add-Result -Stage 'interview-rest-sse' -Status 'PASS' `
        -Evidence "sessionId=$sessionId; version=$($finished.sessionVersion); sse=$sseType"
    } catch {
      Add-Result -Stage 'interview-rest-sse' -Status 'FAIL' -Evidence $_.Exception.Message
    }
  }

  if (-not $token) {
    Add-Result -Stage 'judge0-objective-result' -Status 'SKIPPED' `
      -Evidence 'registration did not complete'
  } else {
    try {
      $problems = @(Invoke-Api -Method Get -Path '/api/algorithms/problems?language=JAVA21' `
          -AccessToken $token)
      if ($problems.Count -eq 0) {
        throw 'no enabled Java 21 problem was returned'
      }
      $problem = Invoke-Api -Method Get `
        -Path "/api/algorithms/problem-versions/$($problems[0].problemVersionId)" `
        -AccessToken $token
      $javaTemplate = @($problem.languages | Where-Object language -eq 'JAVA21') | Select-Object -First 1
      if ($null -eq $javaTemplate) {
        throw 'problem returned no Java 21 template'
      }
      $attempt = Invoke-Api -Method Post -Path '/api/algorithms/attempts' `
        -AccessToken $token -Body @{
          problemVersionId = [long]$problem.problemVersionId
          language = 'JAVA21'
          mode = 'TRAINING'
          contextId = 'release-smoke'
        }
      $submission = Invoke-Api -Method Post `
        -Path "/api/algorithms/attempts/$($attempt.attemptId)/submissions" `
        -AccessToken $token -Body @{
          idempotencyKey = "judge-$([guid]::NewGuid().ToString('N'))"
          sourceCode = [string]$javaTemplate.template
        }
      if ($submission.pendingRejudge -or $submission.status -in @('UNAVAILABLE', 'INTERNAL_ERROR')) {
        Add-Result -Stage 'judge0-objective-result' -Status 'UNVERIFIED' `
          -Evidence "submissionId=$($submission.submissionId); status=$($submission.status)"
      } else {
        Add-Result -Stage 'judge0-objective-result' -Status 'PASS' `
          -Evidence "submissionId=$($submission.submissionId); status=$($submission.status); tests=$($submission.totalCount)"
      }
    } catch {
      Add-Result -Stage 'judge0-objective-result' -Status 'FAIL' -Evidence $_.Exception.Message
    }
  }

  if (-not $token -or -not $sessionCompleted) {
    Add-Result -Stage 'report-profile-training' -Status 'SKIPPED' `
      -Evidence 'a completed interview session is required'
  } else {
    try {
      Invoke-Api -Method Post -Path "/api/reports/sessions/$sessionId/generate" `
        -AccessToken $token | Out-Null
      $report = Wait-Report -SessionId $sessionId -AccessToken $token
      if ($report.status -ne 'COMPLETED') {
        throw "report ended at $($report.status)"
      }
      $profile = @(Invoke-Api -Method Get -Path '/api/capability-profile' `
          -AccessToken $token)
      $atomId = [string]$jobMappings[0].atomId
      $training = Invoke-Api -Method Post -Path '/api/training/tasks' `
        -AccessToken $token -Body @{
          capabilityAtomId = $atomId
          trainingType = 'TECHNICAL_FOUNDATION'
          question = '请根据证据说明该能力的主链路、失败模式和验证方式。'
          evidenceScopes = @('PLATFORM')
        }
      $training = Invoke-Api -Method Post `
        -Path "/api/training/tasks/$($training.taskId)/complete" `
        -AccessToken $token -Body @{
          score = 80
          objectivePassed = $true
          hintUsed = $false
          answerViewed = $false
          redoCount = 0
          observation = 'release smoke contract check'
        }
      if ($training.status -ne 'COMPLETED') {
        throw "training ended at $($training.status)"
      }
      $usage = @(Invoke-Api -Method Get `
          -Path "/api/llm-usage?sessionId=$sessionId&limit=50" -AccessToken $token)
      if ($usage.Count -eq 0) {
        throw 'completed session returned no visible LLM usage record'
      }
      Add-Result -Stage 'report-profile-training' -Status 'PASS' `
        -Evidence "reportId=$($report.reportId); facts=$(@($report.objectiveFacts).Count); profiles=$($profile.Count); usageRecords=$($usage.Count)"
    } catch {
      Add-Result -Stage 'report-profile-training' -Status 'FAIL' -Evidence $_.Exception.Message
    }
  }

  if (-not $SkipCleanup -and $token) {
    $cleanupFailures = 0
    foreach ($cleanup in @(
        @{ Path = if ($knowledgeBaseId) { "/api/knowledgebase/$knowledgeBaseId" } else { $null } },
        @{ Path = if ($githubRepositoryId) { "/api/github/repositories/$githubRepositoryId" } else { $null } },
        @{ Path = if ($jobTargetId) { "/api/job-targets/$jobTargetId" } else { $null } },
        @{ Path = if ($byokConfigured) { '/api/llm-provider/mine' } else { $null } }
      )) {
      if (-not $cleanup.Path) {
        continue
      }
      try {
        Invoke-Api -Method Delete -Path $cleanup.Path -AccessToken $token | Out-Null
      } catch {
        $cleanupFailures++
      }
    }
    Add-Result -Stage 'cleanup' -Status $(if ($cleanupFailures -eq 0) { 'PASS' } else { 'WARN' }) `
      -Evidence "failedOperations=$cleanupFailures"
  } elseif ($SkipCleanup) {
    Add-Result -Stage 'cleanup' -Status 'SKIPPED' -Evidence 'SkipCleanup was requested'
  }
}

$edgeResult = $results | Where-Object stage -eq 'edge-health' | Select-Object -Last 1
$filesResult = $results | Where-Object stage -eq 'private-files-domain' | Select-Object -Last 1
$infrastructurePassed = $edgeResult.status -eq 'PASS' -and (
  $filesResult.status -eq 'PASS' -or
  (-not $RequireFullFlow -and $filesResult.status -eq 'SKIPPED'))
$businessPassed = @($results | Where-Object {
    $_.stage -in $businessStages -and $_.status -ne 'PASS'
  }).Count -eq 0

$summary = [pscustomobject]@{
  baseUrl = $base
  generatedAt = [DateTimeOffset]::UtcNow.ToString('O')
  infrastructurePassed = $infrastructurePassed
  fullFlowPassed = ($infrastructurePassed -and $businessPassed)
  stages = $results
}

$results | Format-Table -AutoSize | Out-String | Write-Host
$json = $summary | ConvertTo-Json -Depth 8
if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
  $resolvedParent = Split-Path -Parent $ReportPath
  if ($resolvedParent -and -not (Test-Path -LiteralPath $resolvedParent)) {
    New-Item -ItemType Directory -Path $resolvedParent -Force | Out-Null
  }
  Set-Content -LiteralPath $ReportPath -Value $json -Encoding UTF8
}
$json

$failed = @($results | Where-Object status -eq 'FAIL')
if ($failed.Count -gt 0) {
  throw "Smoke failed in $($failed.Count) stage(s)"
}
if ($RequireFullFlow -and -not $summary.fullFlowPassed) {
  $requiredFullStages = @('edge-health', 'private-files-domain') + $businessStages
  $incomplete = @($results | Where-Object {
      $_.stage -in $requiredFullStages -and $_.status -ne 'PASS'
    })
  throw "Full product smoke has $($incomplete.Count) unverified or skipped stage(s)"
}
