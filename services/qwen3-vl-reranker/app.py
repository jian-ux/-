import asyncio
from collections import OrderedDict, namedtuple
from dataclasses import dataclass
import hmac
import logging
import os
import re
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
MICROBATCH_MAX_PAIRS = max(1, int(os.getenv("RERANK_MICROBATCH_MAX_PAIRS", "32")))
MICROBATCH_MAX_WAIT_MS = max(0, int(os.getenv("RERANK_MICROBATCH_MAX_WAIT_MS", "8")))
QUEUE_CAPACITY = max(1, int(os.getenv("RERANK_QUEUE_CAPACITY", "128")))
WARMUP_QUERY = os.getenv("RERANK_WARMUP_QUERY", "点签电子合同是什么")
WARMUP_DOCUMENT = os.getenv(
    "RERANK_WARMUP_DOCUMENT", "点签是电子合同签署和管理平台。")

_model: Any = None
_torch: Any = None
_warmup_complete = False
_cache_info_type = namedtuple("CacheInfo", "hits misses maxsize currsize")


class PredictionCache:
    def __init__(self, max_entries: int):
        self.max_entries = max_entries
        self._values: OrderedDict[tuple[str, tuple[str, ...]], tuple[float, ...]] = OrderedDict()
        self.hits = 0
        self.misses = 0

    def get(self, key: tuple[str, tuple[str, ...]]) -> tuple[tuple[float, ...] | None, bool]:
        if self.max_entries <= 0 or key not in self._values:
            self.misses += 1
            return None, False
        self.hits += 1
        self._values.move_to_end(key)
        return self._values[key], True

    def put(self, key: tuple[str, tuple[str, ...]], value: tuple[float, ...]) -> None:
        if self.max_entries <= 0:
            return
        self._values[key] = value
        self._values.move_to_end(key)
        while len(self._values) > self.max_entries:
            self._values.popitem(last=False)

    def clear(self) -> None:
        self._values.clear()
        self.hits = 0
        self.misses = 0

    def info(self):
        return _cache_info_type(
            self.hits, self.misses, self.max_entries, len(self._values))


def _normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip())


def _normalized_key(query: str, documents: list[str] | tuple[str, ...]):
    return _normalize_text(query), tuple(_normalize_text(value) for value in documents)


_prediction_cache = PredictionCache(CACHE_MAX_ENTRIES)


@dataclass(frozen=True)
class BatchResult:
    scores: list[float]
    queue_wait_ms: float
    inference_ms: float
    batch_pairs: int


@dataclass
class _BatchJob:
    query: str
    documents: tuple[str, ...]
    submitted_at: float
    future: asyncio.Future


class InferenceBatcher:
    def __init__(self, predict_fn, max_pairs: int, max_wait_ms: int, queue_capacity: int):
        self.predict_fn = predict_fn
        self.max_pairs = max(1, max_pairs)
        self.max_wait_ms = max(0, max_wait_ms)
        self.queue: asyncio.Queue[_BatchJob | None] = asyncio.Queue(maxsize=max(1, queue_capacity))
        self._task: asyncio.Task | None = None
        self._pending: _BatchJob | None = None
        self.batches = 0
        self.processed_pairs = 0
        self.rejected = 0

    async def start(self) -> None:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        if self._task is None:
            return
        await self.queue.put(None)
        await self._task
        self._task = None

    async def submit(self, query: str, documents: list[str] | tuple[str, ...]) -> BatchResult:
        if self._task is None or self._task.done():
            raise RuntimeError("inference batcher is not running")
        loop = asyncio.get_running_loop()
        future = loop.create_future()
        job = _BatchJob(query, tuple(documents), time.perf_counter(), future)
        try:
            self.queue.put_nowait(job)
        except asyncio.QueueFull:
            self.rejected += 1
            raise
        return await future

    async def _run(self) -> None:
        stopping = False
        while True:
            job = self._pending
            self._pending = None
            if job is None:
                job = await self.queue.get()
            if job is None:
                return

            jobs = [job]
            pair_count = len(job.documents)
            deadline = time.perf_counter() + self.max_wait_ms / 1000
            while pair_count < self.max_pairs and self.max_wait_ms > 0:
                remaining = deadline - time.perf_counter()
                if remaining <= 0:
                    break
                try:
                    next_job = await asyncio.wait_for(self.queue.get(), remaining)
                except asyncio.TimeoutError:
                    break
                if next_job is None:
                    stopping = True
                    break
                if pair_count + len(next_job.documents) > self.max_pairs and jobs:
                    self._pending = next_job
                    break
                jobs.append(next_job)
                pair_count += len(next_job.documents)

            pairs = [
                (current.query, document)
                for current in jobs for document in current.documents]
            started = time.perf_counter()
            try:
                scores = [float(value) for value in await self.predict_fn(pairs)]
                inference_ms = (time.perf_counter() - started) * 1000
                offset = 0
                for current in jobs:
                    size = len(current.documents)
                    current_scores = scores[offset:offset + size]
                    offset += size
                    _prediction_cache.put(
                        _normalized_key(current.query, current.documents),
                        tuple(current_scores))
                    if not current.future.done():
                        current.future.set_result(BatchResult(
                            current_scores,
                            (started - current.submitted_at) * 1000,
                            inference_ms,
                            len(pairs)))
                self.batches += 1
                self.processed_pairs += len(pairs)
            except Exception as error:
                for current in jobs:
                    if not current.future.done():
                        current.future.set_exception(error)
            if stopping:
                return


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
    global _model, _torch, _warmup_complete
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
    _prediction_cache.clear()
    _warm_model()
    _warmup_complete = True
    log.info("Reranker ready")


@asynccontextmanager
async def lifespan(_: FastAPI):
    await asyncio.to_thread(_load_model)
    await _batcher.start()
    try:
        yield
    finally:
        await _batcher.stop()


app = FastAPI(title="Qwen3 Reranker", version="1.0.0", lifespan=lifespan)


def _authorize(authorization: str | None) -> None:
    if not authorization or not hmac.compare_digest(authorization, f"Bearer {API_KEY}"):
        raise HTTPException(status_code=401, detail="Invalid bearer token")


def _predict_cached(query: str, documents: tuple[str, ...]) -> tuple[float, ...]:
    normalized_query, normalized_documents = _normalized_key(query, documents)
    key = normalized_query, normalized_documents
    cached, cache_hit = _prediction_cache.get(key)
    if cache_hit:
        return cached
    scores = _predict_pairs([
        (normalized_query, document) for document in normalized_documents])
    _prediction_cache.put(key, scores)
    return scores


_predict_cached.cache_clear = _prediction_cache.clear
_predict_cached.cache_info = _prediction_cache.info


def _predict_pairs(pairs: list[tuple[str, str]]) -> tuple[float, ...]:
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


def _warm_model() -> None:
    if _model is not None:
        _predict_pairs([(WARMUP_QUERY, WARMUP_DOCUMENT)])


def _predict(query: str, documents: list[str]) -> tuple[list[float], bool]:
    normalized_query, normalized_documents = _normalized_key(query, documents)
    key = normalized_query, normalized_documents
    cached, cache_hit = _prediction_cache.get(key)
    if cache_hit:
        return list(cached), True
    scores = _predict_pairs([
        (normalized_query, document) for document in normalized_documents])
    _prediction_cache.put(key, scores)
    return list(scores), False


async def _predict_batch(pairs: list[tuple[str, str]]) -> tuple[float, ...]:
    return await asyncio.to_thread(_predict_pairs, pairs)


_batcher = InferenceBatcher(
    _predict_batch,
    max_pairs=MICROBATCH_MAX_PAIRS,
    max_wait_ms=MICROBATCH_MAX_WAIT_MS,
    queue_capacity=QUEUE_CAPACITY,
)


def _format_response(model: str, scores: list[float], cache_hit: bool,
                     queue_wait_ms: float = 0, inference_ms: float = 0,
                     cache_lookup_ms: float = 0, total_ms: float = 0,
                     batch_pairs: int = 0) -> dict[str, Any]:
    ranked = sorted(enumerate(scores), key=lambda item: item[1], reverse=True)
    return {
        "model": model,
        "cache_hit": cache_hit,
        "cache_lookup_ms": round(cache_lookup_ms, 2),
        "queue_wait_ms": round(queue_wait_ms, 2),
        "inference_ms": round(inference_ms, 2),
        "latency_ms": round(total_ms, 2),
        "batch_pairs": batch_pairs,
        "results": [
            {"index": index, "relevance_score": score}
            for index, score in ranked
        ],
    }


@app.get("/health")
def health() -> dict[str, Any]:
    gpu = None
    if _torch is not None and _torch.cuda.is_available():
        gpu = _torch.cuda.get_device_name(0)
    cache_info = _prediction_cache.info()
    return {
        "status": "ok" if _model is not None else "loading",
        "model": MODEL_ID,
        "device": DEVICE,
        "gpu": gpu,
        "score_temperature": SCORE_TEMPERATURE,
        "max_length": MAX_LENGTH,
        "batch_size": BATCH_SIZE,
        "warmup_complete": _warmup_complete,
        "queue": {
            "capacity": _batcher.queue.maxsize,
            "depth": _batcher.queue.qsize(),
            "max_pairs": _batcher.max_pairs,
            "max_wait_ms": _batcher.max_wait_ms,
        },
        "metrics": {
            "batches": _batcher.batches,
            "processed_pairs": _batcher.processed_pairs,
            "rejected": _batcher.rejected,
        },
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
    lookup_started = time.perf_counter()
    normalized_query, normalized_documents = _normalized_key(
        request.query, request.documents)
    cache_key = normalized_query, normalized_documents
    cached, cache_hit = _prediction_cache.get(cache_key)
    cache_lookup_ms = (time.perf_counter() - lookup_started) * 1000
    if cache_hit:
        scores = list(cached)
        result = _format_response(
            MODEL_ID, scores, True, cache_lookup_ms=cache_lookup_ms,
            total_ms=(time.perf_counter() - started) * 1000)
    else:
        if _batcher._task is None or _batcher._task.done():
            raise HTTPException(status_code=503, detail="Inference worker is not ready")
        try:
            batch_result = await _batcher.submit(
                normalized_query, normalized_documents)
        except asyncio.QueueFull:
            raise HTTPException(status_code=503, detail="Inference queue is full")
        result = _format_response(
            MODEL_ID, batch_result.scores, False,
            queue_wait_ms=batch_result.queue_wait_ms,
            inference_ms=batch_result.inference_ms,
            cache_lookup_ms=cache_lookup_ms,
            total_ms=(time.perf_counter() - started) * 1000,
            batch_pairs=batch_result.batch_pairs)
    if request.top_n is not None:
        result["results"] = result["results"][:min(request.top_n, len(result["results"]))]
    return result
