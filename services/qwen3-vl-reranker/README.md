# Qwen3-VL-Reranker-2B service

This service exposes the OpenAI-style `POST /rerank` contract used by
`RerankService.java`. The default deployment runs it on the Docker NVIDIA
runtime and persists model files in the `reranker_model_cache` named volume.

Enable the Compose profile in `.env` and start the stack:

```powershell
COMPOSE_PROFILES=reranker
RAG_RERANK_ENABLED=true
docker compose up -d
.\scripts\verify-reranker.ps1
```

The application calls `http://reranker:8091/rerank` over the Compose network.
Port `8091` is also bound to localhost for health checks and the verification
script. The first start builds the CUDA/PyTorch image and downloads the model;
later starts reuse the named volume.

The host-side Python runtime remains available as a development fallback:

```powershell
.\scripts\start-reranker.ps1 -Background -BatchSize 1 -CacheMaxEntries 256
.\scripts\verify-reranker.ps1
```

`RERANK_CACHE_MAX_ENTRIES` controls the bounded in-process prediction cache
(default `256`, set it to `0` to disable it). A hit requires the complete query
and every document string to match. The cache is cleared whenever the process
restarts, so model, instruction, or runtime configuration changes cannot reuse
scores from an older process.

Do not run the host service and Compose service together because both use the
same GPU and localhost port. Remove the legacy scheduled task before switching
to Compose:

```powershell
.\scripts\uninstall-reranker-task.ps1
```

To stop only the Compose reranker while leaving the rest of the stack running:

```powershell
docker compose stop reranker
```
