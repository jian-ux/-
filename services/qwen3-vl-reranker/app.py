import asyncio
import hmac
import logging
import os
import time
from contextlib import asynccontextmanager
from functools import lru_cache
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field, field_validator


logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
log = logging.getLogger("qwen3-reranker")

MODEL_ID = os.getenv("RERANK_MODEL", "Qwen/Qwen3-Reranker-0.6B")
DEVICE = os.getenv("RERANK_DEVICE", "cuda")
DTYPE = os.getenv("RERANK_DTYPE", "bfloat16")
INSTRUCTION = os.getenv(
    "RERANK_INSTRUCTION",
    "Given a customer service question, retrieve passages containing facts needed to answer it. "
    "Treat passages that correct, limit, or contradict an assumption in the question as relevant "
    "evidence, even when they do not affirm the assumption.",
)
MAX_CANDIDATES = int(os.getenv("RERANK_MAX_CANDIDATES", "10"))
MAX_LENGTH = int(os.getenv("RERANK_MAX_LENGTH", "1024"))
BATCH_SIZE = int(os.getenv("RERANK_BATCH_SIZE", "8"))
CACHE_MAX_ENTRIES = max(0, int(os.getenv("RERANK_CACHE_MAX_ENTRIES", "1024")))
SCORE_TEMPERATURE = max(0.1, float(os.getenv("RERANK_SCORE_TEMPERATURE", "1.0")))
API_KEY = os.getenv("RERANK_API_KEY", "").strip()

_model: Any = None
_torch: Any = None
_inference_lock = asyncio.Lock()


class RerankRequest(BaseModel):
    model: str | None = None
    query: str = Field(min_length=1, max_length=4000)
    documents: list[str] = Field(min_length=1)
    top_n: int | None = Field(default=None, ge=1)

    @field_validator("query")
    @classmethod
    def query_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("query must not be blank")
        return value

    @field_validator("documents")
    @classmethod
    def documents_must_be_valid(cls, values: list[str]) -> list[str]:
        if len(values) > MAX_CANDIDATES:
            raise ValueError(f"at most {MAX_CANDIDATES} documents are allowed")
        if any(not isinstance(value, str) or not value.strip() for value in values):
            raise ValueError("documents must contain non-blank strings")
        return values


def _load_model() -> None:
    global _model, _torch
    import torch
    from sentence_transformers import CrossEncoder

    if not API_KEY:
        raise RuntimeError("RERANK_API_KEY must be configured")
    if DEVICE.startswith("cuda") and not torch.cuda.is_available():
        raise RuntimeError("CUDA is required but no CUDA device is available")
    if DEVICE.startswith("cuda"):
        torch.set_float32_matmul_precision("high")
        torch.backends.cuda.matmul.allow_tf32 = True

    dtype = getattr(torch, DTYPE, None)
    if dtype is None:
        raise RuntimeError(f"Unsupported RERANK_DTYPE: {DTYPE}")

    log.info("Loading %s on %s with %s", MODEL_ID, DEVICE, DTYPE)
    _model = CrossEncoder(
        MODEL_ID,
        device=DEVICE,
        max_length=MAX_LENGTH,
        trust_remote_code=True,
        model_kwargs={
            "torch_dtype": dtype,
            "attn_implementation": "sdpa",
        },
    )
    _torch = torch
    _predict_cached.cache_clear()
    log.info("Reranker ready")


@asynccontextmanager
async def lifespan(_: FastAPI):
    await asyncio.to_thread(_load_model)
    yield


app = FastAPI(title="Qwen3 Reranker", version="1.0.0", lifespan=lifespan)


def _authorize(authorization: str | None) -> None:
    if not authorization or not hmac.compare_digest(authorization, f"Bearer {API_KEY}"):
        raise HTTPException(status_code=401, detail="Invalid bearer token")


@lru_cache(maxsize=CACHE_MAX_ENTRIES)
def _predict_cached(query: str, documents: tuple[str, ...]) -> tuple[float, ...]:
    pairs = [(query, document) for document in documents]
    scores = _model.predict(
        pairs,
        prompt=INSTRUCTION,
        batch_size=max(1, BATCH_SIZE),
        activation_fn=lambda values: _torch.sigmoid(
            values.float() / SCORE_TEMPERATURE),
        show_progress_bar=False,
        convert_to_numpy=True,
    )
    return tuple(float(score) for score in scores)


def _predict(query: str, documents: list[str]) -> tuple[list[float], bool]:
    hits_before = _predict_cached.cache_info().hits
    scores = _predict_cached(query, tuple(documents))
    cache_hit = _predict_cached.cache_info().hits > hits_before
    return list(scores), cache_hit


@app.get("/health")
def health() -> dict[str, Any]:
    gpu = None
    if _torch is not None and _torch.cuda.is_available():
        gpu = _torch.cuda.get_device_name(0)
    cache_info = _predict_cached.cache_info()
    return {
        "status": "ok" if _model is not None else "loading",
        "model": MODEL_ID,
        "device": DEVICE,
        "gpu": gpu,
        "score_temperature": SCORE_TEMPERATURE,
        "max_length": MAX_LENGTH,
        "batch_size": BATCH_SIZE,
        "cache": {
            "max_entries": CACHE_MAX_ENTRIES,
            "entries": cache_info.currsize,
            "hits": cache_info.hits,
            "misses": cache_info.misses,
        },
    }


@app.post("/rerank")
async def rerank(
    request: RerankRequest,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    _authorize(authorization)
    if _model is None:
        raise HTTPException(status_code=503, detail="Model is not ready")
    if request.model and request.model != MODEL_ID:
        raise HTTPException(
            status_code=400,
            detail=f"Requested model {request.model!r} does not match loaded model {MODEL_ID!r}",
        )

    started = time.perf_counter()
    async with _inference_lock:
        scores, cache_hit = await asyncio.to_thread(
            _predict, request.query, request.documents)

    top_n = min(request.top_n or len(scores), len(scores))
    ranked = sorted(enumerate(scores), key=lambda item: item[1], reverse=True)[:top_n]
    return {
        "model": MODEL_ID,
        "cache_hit": cache_hit,
        "latency_ms": round((time.perf_counter() - started) * 1000, 2),
        "results": [
            {"index": index, "relevance_score": score}
            for index, score in ranked
        ],
    }
