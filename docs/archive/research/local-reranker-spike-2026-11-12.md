# 本地 Reranker Spike：Qwen3-Reranker-0.6B readiness（2026-11-12）

> 状态：**READY / NOT_RUN（full 8/20/50）**
> 范围：只回答“本机 8/20/50 候选能否在 3s 内完成”的准备阶段；不涉及生产切换。
> 说明：本文件独立于 JMeter 性能基线；JMeter 结果不引用本文件的任何延迟数字。

## 0. 结论先行

- 模型、量化和 CPU/GPU 运行路径已就绪；**8/20/50 全量延迟、NDCG@8、失败率、VRAM 尚未测量**。
- 原因是按用户“先收尾”的指令，在启动全量推理前停止；因此**不能回答 3s 是否达标**。
- 已完成的不是性能结论，而是一个 2 文档 `/rerank` 冒烟：分值方向正确，但 5.4s 是包含模型加载的冷启动，不是 8/20/50 样本。
- 未修改 `RERANK_MODE` 或任何产品默认开关；BGE-reranker-v2-m3 未下载。

## 1. 模型与运行环境

| 项 | 值 |
| --- | --- |
| 模型 | `Qwen/Qwen3-Reranker-0.6B` |
| 本地 safetensors | `D:\models\Qwen3-Reranker-0.6B\model.safetensors` |
| safetensors size / SHA-256 | `1,191,588,280` / `27cd75a405b9c1b46b59abfd88aaa209e6fed2a1972cde9b70e7659537c5e65b`（与 HF LFS 一致） |
| tokenizer size / SHA-256 | `11,422,654` / `aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4`（与 HF LFS 一致） |
| 运行权重 | `D:\models\qwen3-reranker-0.6b-q8_0.gguf` |
| GGUF size / SHA-256 | `639,153,184` / `22c9979ce4fbcdc5acdc310c6641c32797eff1aa980b8f7a2db8a8ea23429a48`（与 HF LFS 一致） |
| 量化 | Q8_0 |
| GPU | NVIDIA GeForce RTX 4060 Laptop，8GB；驱动 591.74，CUDA 13.1 |
| 运行时 | llama.cpp `b12318` Windows Vulkan 构建；`--rerank -ngl 99 -c 8192`；4 slots |
| 其他 | Vulkan 路径禁用了 Flash Attention；CPU 线程 24 |

safetensors、tokenizer 和 GGUF 均已完整校验，**没有用半截文件推理**。Ollama 曾尝试导入 GGUF，但其 generate 能力拒绝该 reranker 模型（`does not support generate`）；该 Ollama 模型已删除，未用于测量。最终运行路径是 llama.cpp 原生 `/rerank`。

## 2. 官方 prompt / yes-no 路径

使用 Qwen3-Reranker 官方格式：

```text
<|im_start|>system
Judge whether the Document meets the requirements based on the Query and the Instruct provided. Note that the answer can only be "yes" or "no".<|im_end|>
<|im_start|>user
<Instruct>: Given a web search query, retrieve relevant passages that answer the query
<Query>: {query}
<Document>: {title}\n{text}<|im_end|>
<|im_start|>assistant
<think>

</think>
```

由 llama.cpp 的原生 rerank 路径给出 relevance score；没有使用 sentence-transformers 默认 `CrossEncoder`，也没有使用 Ollama 的默认 chat template。

## 3. 已完成的冒烟

请求：query=`里程碑延期后如何重排依赖任务`，2 个文档。

| 文档 | relevance_score |
| --- | ---: |
| 里程碑变更与依赖重排 | 0.9999814 |
| 甘特图视图说明 | 0.2286905 |

- `usage.prompt_tokens=211`；分值方向符合预期。
- wall time `5.4s` 是**冷启动**（模型加载 + 首次请求），不是 8/20/50 延迟样本，不能用于 3s 结论。
- 证据：[llama-server-smoke.txt](../../acceptance-evidence/2026-11-12/local-reranker-spike/llama-server-smoke.txt)。

## 4. 全量测量夹具（已就绪，未执行）

`tests/performance/reranker/qwen_llamacpp_rerank.py`：

- 读取冻结集 `ai-collab-ai-worker/tests/fixtures/rerank_set/rerank_baseline.v1.jsonl`（15 cases，原 8 候选）；
- 20/50 候选用确定性合成零标签候选补齐，仅用于延迟；NDCG@8 只使用原 8 个候选，避免把补齐候选混入质量口径；
- 每个档位 warm-up 一次，再跑 3 次 × 15 cases；
- 记录每 query 的 `/rerank` wall time、P50/P95/P99、失败率、prompt tokens、GPU 显存；
- NDCG@8 使用冻结集的 `2^rel-1 / log2(rank+1)` 口径；
- 只读本地，不改产品代码、默认开关或数据库。

运行示例：

```powershell
E:\project\ai-collab\target\perf-tools\llama-b12318-vulkan\llama-server.exe `
  -m D:\models\qwen3-reranker-0.6b-q8_0.gguf `
  --host 127.0.0.1 --port 18081 -ngl 99 -c 8192 --rerank

py tests/performance/reranker/qwen_llamacpp_rerank.py `
  --server-url http://127.0.0.1:18081 `
  --targets 8,20,50 --repetitions 3 `
  --output docs/acceptance-evidence/2026-11-12/local-reranker-spike/results.json `
  --safetensors-sha256 27cd75a405b9c1b46b59abfd88aaa209e6fed2a1972cde9b70e7659537c5e65b `
  --gguf-sha256 22c9979ce4fbcdc5acdc310c6641c32797eff1aa980b8f7a2db8a8ea23429a48
```

## 5. NOT_VERIFIED

- Qwen3-Reranker-0.6B 的 8/20/50 P50/P95/P99、失败率、NDCG@8、峰值 VRAM；
- 8/20/50 是否能落入 3s 预算；
- Q8_0 GGUF 与 full-precision safetensors 的质量/延迟差异；
- BGE-reranker-v2-m3（未下载，按“Qwen 先跑完再下”的约束）；
- 任何生产 rerank 切换、并发/队列/多实例或真实业务流量。

## 6. 收尾

- llama.cpp server 已停止，Ollama 临时模型已删除；模型文件只保留在 `D:\models`，未进入仓库。
- 未改产品代码、迁移、前端或默认开关；`RERANK_MODE` 未动。
- 本报告与 JMeter 报告完全分离；下一步在单独窗口跑 Qwen 8/20/50，再决定是否下载/测试 BGE。
