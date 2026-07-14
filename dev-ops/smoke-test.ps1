param(
  [string]$BaseUrl = 'http://127.0.0.1:8082'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot

function Invoke-Api {
  param(
    [string]$Method,
    [string]$Uri,
    [hashtable]$Headers = @{},
    $Body
  )

  $request = @{
    Method = $Method
    Uri = $Uri
    Headers = $Headers
    ContentType = 'application/json; charset=utf-8'
  }
  if ($null -ne $Body) {
    $request.Body = $Body | ConvertTo-Json -Depth 12 -Compress
  }
  $response = Invoke-RestMethod @request
  if ($null -ne $response.code -and $response.code -notin @(0, 200)) {
    throw "API failed: $Uri code=$($response.code) message=$($response.message)"
  }
  return $response.data
}

function Get-EnvValue {
  param([string]$Name)

  $line = Get-Content -LiteralPath (Join-Path $repoRoot '.env') -Encoding UTF8 |
    Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
    Select-Object -First 1
  if (-not $line) {
    throw "Missing $Name in .env"
  }
  return $line.Substring($line.IndexOf('=') + 1).Trim().Trim('"').Trim("'")
}

$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$username = "smoke$stamp"
$auth = Invoke-Api -Method Post -Uri "$BaseUrl/api/auth/register" -Body @{
  username = $username
  email = "$username@example.test"
  password = 'SmokePass123!'
  displayName = '主链路验收用户'
}
$headers = @{ Authorization = "Bearer $($auth.accessToken)" }

Invoke-Api -Method Put -Uri "$BaseUrl/api/llm-provider/mine" -Headers $headers -Body @{
  baseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
  apiKey = Get-EnvValue 'AI_BAILIAN_API_KEY'
  chatModel = 'qwen3.5-flash'
  temperature = 0.2
} | Out-Null
$provider = Invoke-Api -Method Post -Uri "$BaseUrl/api/llm-provider/mine/test" -Headers $headers
if (-not $provider.success) {
  throw "BYOK test failed: $($provider.message)"
}

$document = Get-Item -LiteralPath (
  Join-Path $repoRoot 'backend/src/main/resources/skills/ai-agent-dev/ai-agent-dev.md')
$upload = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/knowledgebase/upload" `
  -Headers $headers -Form @{
    file = $document
    name = "主链路验收知识库 $stamp"
    category = 'AI_AGENT'
  }
if ($upload.code -notin @(0, 200)) {
  throw "Upload failed: $($upload.message)"
}
$knowledgeBaseId = [long]$upload.data.knowledgeBase.id
Invoke-Api -Method Post -Uri "$BaseUrl/api/knowledgebase/$knowledgeBaseId/split" `
  -Headers $headers -Body @{} | Out-Null

$knowledgeBaseStatus = $null
for ($attempt = 0; $attempt -lt 60; $attempt++) {
  $knowledgeBase = Invoke-Api -Method Get `
    -Uri "$BaseUrl/api/knowledgebase/$knowledgeBaseId" -Headers $headers
  $knowledgeBaseStatus = [string]$knowledgeBase.docStatus
  if ($knowledgeBaseStatus -eq 'VECTOR_STORED') {
    break
  }
  Start-Sleep -Seconds 2
}
if ($knowledgeBaseStatus -ne 'VECTOR_STORED') {
  throw "Vectorization timed out: id=$knowledgeBaseId status=$knowledgeBaseStatus"
}

$rag = Invoke-Api -Method Post -Uri "$BaseUrl/api/knowledgebase/query" -Headers $headers -Body @{
  knowledgeBaseIds = @($knowledgeBaseId)
  question = '请解释 Plan-and-Execute 与 Reflection 如何组合，并给出适用边界'
}
if (-not $rag.answer) {
  throw 'RAG query returned no answer'
}

$session = Invoke-Api -Method Post -Uri "$BaseUrl/api/interview/sessions" `
  -Headers $headers -Body @{
    resumeText = 'Java 后端开发，熟悉 RAG、消息队列与 Agent 工作流'
    questionCount = 3
    forceCreate = $true
    skillId = 'ai-agent-dev'
    difficulty = 'mid'
    knowledgeBaseIds = @($knowledgeBaseId)
  }
$sessionId = [string]$session.sessionId
$answers = @(
  'Planner 先产出能力覆盖计划，Interviewer 根据回答和证据决定下一题，Critic 检查重复、难度和证据引用，显式状态机约束动作。',
  'RAG 使用混合召回、RRF 融合与 Rerank 精排；证据 ID 只能来自召回白名单，弱召回应澄清或切换主题。',
  '异步评估用幂等键防重复；报告已生成但画像失败时，从持久化报告恢复观察，不再次调用模型。'
)
for ($index = 0; $index -lt $answers.Count; $index++) {
  Invoke-Api -Method Post -Uri "$BaseUrl/api/interview/sessions/$sessionId/answers" `
    -Headers $headers -Body @{ questionIndex = $index; answer = $answers[$index] } | Out-Null
}

$persistedStatus = $null
for ($attempt = 0; $attempt -lt 90; $attempt++) {
  $sessions = Invoke-Api -Method Get -Uri "$BaseUrl/api/interview/sessions" -Headers $headers
  $persisted = @($sessions) | Where-Object { $_.sessionId -eq $sessionId } | Select-Object -First 1
  $persistedStatus = [string]$persisted.status
  if ($persistedStatus -eq 'EVALUATED') {
    break
  }
  Start-Sleep -Seconds 2
}
if ($persistedStatus -ne 'EVALUATED') {
  throw "Evaluation timed out: session=$sessionId status=$persistedStatus"
}

$finalSession = Invoke-Api -Method Get -Uri "$BaseUrl/api/interview/sessions/$sessionId" `
  -Headers $headers
$report = Invoke-Api -Method Get -Uri "$BaseUrl/api/interview/sessions/$sessionId/report" `
  -Headers $headers
$profile = Invoke-Api -Method Get `
  -Uri "$BaseUrl/api/interview/candidate-memory/profile?skillId=ai-agent-dev" -Headers $headers
$trace = Invoke-Api -Method Get -Uri "$BaseUrl/api/interview/sessions/$sessionId/agent-trace" `
  -Headers $headers
$decisions = @($finalSession.questions | Where-Object { $_.followUpAction })
$evidenceIds = @($finalSession.questions | ForEach-Object { $_.evidenceIds } | Where-Object { $_ })
if ($finalSession.status -ne 'EVALUATED' -or -not $report `
    -or @($profile).Count -eq 0 -or @($trace).Count -eq 0 `
    -or $decisions.Count -eq 0 -or $evidenceIds.Count -eq 0) {
  throw 'Mainline assertions failed: report/profile/trace/decision/evidence must be present'
}

[pscustomobject]@{
  knowledgeBaseId = $knowledgeBaseId
  knowledgeBaseStatus = $knowledgeBaseStatus
  ragAnswerPresent = [bool]$rag.answer
  sessionId = $sessionId
  sessionStatus = [string]$finalSession.status
  persistedSessionStatus = $persistedStatus
  decisionCount = $decisions.Count
  evidenceIdCount = $evidenceIds.Count
  profileAtomCount = @($profile).Count
  traceGroupCount = @($trace).Count
} | ConvertTo-Json -Compress
