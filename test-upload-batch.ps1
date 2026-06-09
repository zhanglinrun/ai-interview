# 批量上传知识库文档性能测试脚本
# 测试配置：parallelism=1（串行基线）或 parallelism=3（并行）

param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$DocDir = "C:\Users\zlr\Desktop\456",
    [int]$MaxDocs = 12  # 限制上传前 12 个文档
)

Write-Host "======================================" -ForegroundColor Cyan
Write-Host "批量上传知识库文档性能测试" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "Base URL: $BaseUrl"
Write-Host "Document Directory: $DocDir"
Write-Host "Max Documents: $MaxDocs"
Write-Host ""

# 1. 创建知识库
Write-Host "[Step 1] 创建知识库..." -ForegroundColor Yellow
$createKbBody = @{
    name = "性能测试-批量上传-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    description = "批量上传性能测试知识库"
} | ConvertTo-Json -Depth 10

try {
    $createResponse = Invoke-RestMethod -Uri "$BaseUrl/api/kb/create" `
        -Method Post `
        -ContentType "application/json" `
        -Body $createKbBody
    
    if ($createResponse.success -ne $true) {
        Write-Host "创建知识库失败: $($createResponse.message)" -ForegroundColor Red
        exit 1
    }
    
    $kbId = $createResponse.data.id
    Write-Host "知识库创建成功: ID=$kbId" -ForegroundColor Green
} catch {
    Write-Host "创建知识库异常: $_" -ForegroundColor Red
    exit 1
}

# 2. 获取要上传的文档列表
Write-Host ""
Write-Host "[Step 2] 扫描文档..." -ForegroundColor Yellow
$docs = Get-ChildItem -Path $DocDir -Recurse -File -Include "*.pdf" | Select-Object -First $MaxDocs
Write-Host "找到 $($docs.Count) 个文档，总大小: $([math]::Round(($docs | Measure-Object -Property Length -Sum).Sum / 1MB, 2)) MB"

# 3. 批量上传
Write-Host ""
Write-Host "[Step 3] 批量上传文档..." -ForegroundColor Yellow
$uploadResults = @()
$startTime = Get-Date

foreach ($doc in $docs) {
    $docStartTime = Get-Date
    Write-Host "  上传: $($doc.Name) ($([math]::Round($doc.Length / 1MB, 2)) MB)..."
    
    try {
        $boundary = [System.Guid]::NewGuid().ToString()
        $LF = "`r`n"
        $fileContent = [System.IO.File]::ReadAllBytes($doc.FullName)
        
        $bodyLines = @(
            "--$boundary",
            "Content-Disposition: form-data; name=`"file`"; filename=`"$($doc.Name)`"",
            "Content-Type: application/pdf$LF",
            [System.Text.Encoding]::GetEncoding("ISO-8859-1").GetString($fileContent),
            "--$boundary--$LF"
        ) -join $LF
        
        $uploadResponse = Invoke-RestMethod -Uri "$BaseUrl/api/kb/$kbId/upload" `
            -Method Post `
            -ContentType "multipart/form-data; boundary=$boundary" `
            -Body ([System.Text.Encoding]::GetEncoding("ISO-8859-1").GetBytes($bodyLines))
        
        $docEndTime = Get-Date
        $docElapsed = ($docEndTime - $docStartTime).TotalSeconds
        
        if ($uploadResponse.success -eq $true) {
            Write-Host "    ✓ 成功 (耗时: $([math]::Round($docElapsed, 2))s)" -ForegroundColor Green
            $uploadResults += @{
                FileName = $doc.Name
                Size = $doc.Length
                Success = $true
                Elapsed = $docElapsed
            }
        } else {
            Write-Host "    ✗ 失败: $($uploadResponse.message)" -ForegroundColor Red
            $uploadResults += @{
                FileName = $doc.Name
                Size = $doc.Length
                Success = $false
                Error = $uploadResponse.message
            }
        }
    } catch {
        $docEndTime = Get-Date
        $docElapsed = ($docEndTime - $docStartTime).TotalSeconds
        Write-Host "    ✗ 异常: $_" -ForegroundColor Red
        $uploadResults += @{
            FileName = $doc.Name
            Size = $doc.Length
            Success = $false
            Error = $_.Exception.Message
            Elapsed = $docElapsed
        }
    }
}

$endTime = Get-Date
$totalElapsed = ($endTime - $startTime).TotalSeconds

# 4. 等待向量化完成
Write-Host ""
Write-Host "[Step 4] 等待向量化完成..." -ForegroundColor Yellow
Write-Host "  轮询知识库状态，等待所有文档向量化完成（最多等待 10 分钟）..."

$maxWaitSeconds = 600
$pollInterval = 5
$waitStart = Get-Date

while ($true) {
    Start-Sleep -Seconds $pollInterval
    
    try {
        $statusResponse = Invoke-RestMethod -Uri "$BaseUrl/api/kb/$kbId" -Method Get
        
        if ($statusResponse.success -eq $true) {
            $kb = $statusResponse.data
            $totalDocs = $kb.documentCount
            
            # 查询向量化状态（通过 actuator metrics）
            try {
                $metricsResponse = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/app.ai.vectorize.documents" -Method Get
                $successCount = ($metricsResponse.measurements | Where-Object { $_.statistic -eq "COUNT" }).value
                
                Write-Host "  向量化进度: $successCount / $totalDocs 文档完成" -ForegroundColor Cyan
                
                if ($successCount -ge $totalDocs) {
                    Write-Host "  ✓ 所有文档向量化完成！" -ForegroundColor Green
                    break
                }
            } catch {
                # 指标还未生成，继续等待
                Write-Host "  等待向量化任务启动..." -ForegroundColor Gray
            }
        }
    } catch {
        Write-Host "  查询状态失败: $_" -ForegroundColor Red
    }
    
    $waitElapsed = ((Get-Date) - $waitStart).TotalSeconds
    if ($waitElapsed -gt $maxWaitSeconds) {
        Write-Host "  ✗ 超时：等待超过 $maxWaitSeconds 秒" -ForegroundColor Red
        break
    }
}

$vectorizeEndTime = Get-Date
$totalVectorizeTime = ($vectorizeEndTime - $startTime).TotalSeconds

# 5. 输出汇总报告
Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "性能测试报告" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "知识库 ID: $kbId"
Write-Host "上传文档数: $($uploadResults.Count)"
Write-Host "成功上传: $(($uploadResults | Where-Object { $_.Success -eq $true }).Count)"
Write-Host "失败上传: $(($uploadResults | Where-Object { $_.Success -ne $true }).Count)"
Write-Host ""
Write-Host "上传阶段耗时: $([math]::Round($totalElapsed, 2)) 秒"
Write-Host "向量化总耗时: $([math]::Round($totalVectorizeTime, 2)) 秒（上传 + 等待向量化完成）"
Write-Host ""

# 查询最终指标
try {
    $finalMetrics = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/app.ai.vectorize.document_latency" -Method Get
    $avgLatency = ($finalMetrics.measurements | Where-Object { $_.statistic -eq "MEAN" }).value
    $maxLatency = ($finalMetrics.measurements | Where-Object { $_.statistic -eq "MAX" }).value
    
    Write-Host "单文档平均耗时: $([math]::Round($avgLatency, 2)) 秒"
    Write-Host "单文档最大耗时: $([math]::Round($maxLatency, 2)) 秒"
} catch {
    Write-Host "无法获取向量化指标" -ForegroundColor Gray
}

Write-Host ""
Write-Host "测试完成！" -ForegroundColor Green
