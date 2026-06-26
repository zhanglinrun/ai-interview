# 本地 ONNX Reranker 模型

本目录存放本地 BGE-RERANKER 重排模型文件，供 `LocalOnnxRerankModel` 进程内精排使用（对齐 know-engine 的 `BgeScoringModel`）。

## 文件清单（需手动下载，不入 git）

模型文件较大（~400MB），已在 `backend/.gitignore` 排除，不会提交。请手动下载以下两个文件放入本目录：

| 文件 | 来源 | 说明 |
|------|------|------|
| `model_quantized.onnx` | `onnx-community/bge-reranker-v2-m3-ONNX` | 量化版 ONNX 模型（CPU 推理推荐） |
| `tokenizer.json` | `onnx-community/bge-reranker-v2-m3-ONNX` | HuggingFace tokenizer |

## 下载方式

### 方式一：HuggingFace 官网下载

1. 访问 https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX
2. 下载 `onnx/model_quantized.onnx` → 重命名为 `model_quantized.onnx` 放入本目录
3. 下载 `tokenizer.json` → 放入本目录

### 方式二：huggingface-cli（推荐，支持断点续传）

```bash
pip install -U "huggingface_hub[cli]"
huggingface-cli download onnx-community/bge-reranker-v2-m3-ONNX \
  onnx/model_quantized.onnx tokenizer.json \
  --local-dir ./tmp-bge \
  --local-dir-use-symlinks False
# 把文件拷贝到本目录
cp tmp-bge/onnx/model_quantized.onnx ./model_quantized.onnx
cp tmp-bge/tokenizer.json ./tokenizer.json
rm -rf tmp-bge
```

### 方式三：镜像源（国内网络）

```bash
export HF_ENDPOINT=https://hf-mirror.com
huggingface-cli download onnx-community/bge-reranker-v2-m3-ONNX \
  onnx/model_quantized.onnx tokenizer.json \
  --local-dir ./tmp-bge --local-dir-use-symlinks False
```

## 缺失时的行为

**模型文件缺失时不会启动失败**：`LocalOnnxRerankModel` 加载失败会 `log.warn` 并标记不可用，
`RerankService` 路由层自动降级到 DashScope `gte-rerank-v2` 云端 rerank（需要 `AI_BAILIAN_API_KEY`）。
若云端也不可用，退回等分（0.0）让上层 `ReRankingContentAggregator` 退回原序。

## 配置

路径与 `maxSequenceLength` 走 `app.ai.rag.rerank.local` 配置（`KnowledgeBaseQueryProperties.Rerank.LocalOnnx`），
默认值即指向本目录，可按需覆盖：

```yaml
app:
  ai:
    rag:
      rerank:
        provider: local          # local | cloud
        local:
          model-path: classpath:model/bge-reranker-model/model_quantized.onnx
          tokenizer-path: classpath:model/bge-reranker-model/tokenizer.json
          max-sequence-length: 8192
```

## 切换为纯云端

不需要本地模型时，把 provider 切到 cloud 即可（仍保留云端兜底）：

```yaml
app.ai.rag.rerank.provider: cloud
```
