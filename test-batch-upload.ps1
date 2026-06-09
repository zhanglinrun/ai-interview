# Batch upload test - simplified version
$baseUrl = "http://localhost:8081"
$docDir = "C:\Users\zlr\Desktop\456"
$maxDocs = 12

Write-Host "======================================"
Write-Host "Batch Upload Performance Test"
Write-Host "======================================"

# Step 1: Create knowledge base
Write-Host "`n[Step 1] Creating knowledge base..."
$kbName = "perf-test-" + (Get-Date -Format "yyyyMMdd-HHmmss")
$createBody = "{`"name`":`"$kbName`",`"description`":`"Performance test`"}"

$createResp = Invoke-RestMethod -Uri "$baseUrl/api/kb/create" -Method Post -ContentType "application/json" -Body $createBody
$kbId = $createResp.data.id
Write-Host "Knowledge base created: ID=$kbId"

# Step 2: Get documents
Write-Host "`n[Step 2] Scanning documents..."
$docs = Get-ChildItem -Path $docDir -File -Filter "*.pdf" | Select-Object -First $maxDocs
$totalSize = ($docs | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Host "Found $($docs.Count) documents, total size: $([math]::Round($totalSize, 2)) MB"

# Step 3: Upload documents
Write-Host "`n[Step 3] Uploading documents..."
$startTime = Get-Date

foreach ($doc in $docs) {
    Write-Host "  Uploading: $($doc.Name) ..."
    
    $form = @{
        file = Get-Item -Path $doc.FullName
    }
    
    try {
        $uploadResp = Invoke-RestMethod -Uri "$baseUrl/api/kb/$kbId/upload" -Method Post -Form $form
        if ($uploadResp.success) {
            Write-Host "    OK" -ForegroundColor Green
        } else {
            Write-Host "    FAILED: $($uploadResp.message)" -ForegroundColor Red
        }
    } catch {
        Write-Host "    ERROR: $($_.Exception.Message)" -ForegroundColor Red
    }
}

$uploadEnd = Get-Date
$uploadElapsed = ($uploadEnd - $startTime).TotalSeconds
Write-Host "`nUpload completed in $([math]::Round($uploadElapsed, 2)) seconds"

# Step 4: Wait for vectorization
Write-Host "`n[Step 4] Waiting for vectorization to complete..."
$maxWait = 600
$pollStart = Get-Date

while ($true) {
    Start-Sleep -Seconds 5
    
    try {
        $metricsResp = Invoke-RestMethod -Uri "$baseUrl/actuator/metrics/app.ai.vectorize.documents" -Method Get
        $measurements = $metricsResp.measurements | Where-Object { $_.statistic -eq "COUNT" }
        if ($measurements) {
            $successCount = $measurements.value
            Write-Host "  Vectorization progress: $successCount / $($docs.Count)"
            
            if ($successCount -ge $docs.Count) {
                Write-Host "  All documents vectorized!" -ForegroundColor Green
                break
            }
        }
    } catch {
        Write-Host "  Waiting for metrics..." -ForegroundColor Gray
    }
    
    $elapsed = ((Get-Date) - $pollStart).TotalSeconds
    if ($elapsed -gt $maxWait) {
        Write-Host "  Timeout after $maxWait seconds" -ForegroundColor Red
        break
    }
}

$totalEnd = Get-Date
$totalElapsed = ($totalEnd - $startTime).TotalSeconds

# Step 5: Get final metrics
Write-Host "`n======================================"
Write-Host "Performance Test Report"
Write-Host "======================================"
Write-Host "Knowledge Base ID: $kbId"
Write-Host "Documents uploaded: $($docs.Count)"
Write-Host "Total size: $([math]::Round($totalSize, 2)) MB"
Write-Host "Upload time: $([math]::Round($uploadElapsed, 2)) seconds"
Write-Host "Total time (upload + vectorization): $([math]::Round($totalElapsed, 2)) seconds"

try {
    $latencyMetrics = Invoke-RestMethod -Uri "$baseUrl/actuator/metrics/app.ai.vectorize.document_latency" -Method Get
    $mean = ($latencyMetrics.measurements | Where-Object { $_.statistic -eq "MEAN" }).value
    $max = ($latencyMetrics.measurements | Where-Object { $_.statistic -eq "MAX" }).value
    Write-Host "Average document latency: $([math]::Round($mean, 2)) seconds"
    Write-Host "Max document latency: $([math]::Round($max, 2)) seconds"
} catch {
    Write-Host "Could not fetch latency metrics"
}

Write-Host "`nTest completed!"
