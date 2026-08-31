import asyncio
import unittest

import app


class _FakeModel:
    def __init__(self):
        self.calls = 0
        self.last_temperature = None

    def predict(self, pairs, **kwargs):
        self.calls += 1
        values = _FakeValues(
            [0.75 - index * 0.1 for index, _pair in enumerate(pairs)])
        scores = kwargs["activation_fn"](values)
        self.last_temperature = values.divisor
        return scores


class _FakeValues:
    def __init__(self, scores):
        self.scores = scores
        self.converted_to_float = False
        self.divisor = None

    def float(self):
        self.converted_to_float = True
        return self

    def __truediv__(self, divisor):
        if not self.converted_to_float:
            raise AssertionError("logits must be converted to float before scaling")
        self.divisor = divisor
        return self


class _FakeTorch:
    @staticmethod
    def sigmoid(values):
        return values.scores


class PredictionCacheTest(unittest.TestCase):
    def setUp(self):
        self.original_model = app._model
        self.original_torch = app._torch
        self.model = _FakeModel()
        app._model = self.model
        app._torch = _FakeTorch()
        app._predict_cached.cache_clear()

    def tearDown(self):
        app._predict_cached.cache_clear()
        app._model = self.original_model
        app._torch = self.original_torch

    def test_reuses_only_identical_query_and_document_inputs(self):
        documents = ["document one", "document two"]

        first_scores, first_cache_hit = app._predict("same query", documents)
        second_scores, second_cache_hit = app._predict(
            "  same   query ", ["document one", " document two "])
        changed_scores, changed_cache_hit = app._predict(
            "same query", ["document one", "changed document"])

        self.assertEqual(first_scores, second_scores)
        self.assertEqual(first_scores, changed_scores)
        self.assertFalse(first_cache_hit)
        self.assertTrue(second_cache_hit)
        self.assertFalse(changed_cache_hit)
        self.assertEqual(2, self.model.calls)
        self.assertEqual(app.SCORE_TEMPERATURE, self.model.last_temperature)


class InferenceBatcherTest(unittest.TestCase):
    def test_batches_concurrent_requests_and_preserves_each_result_slice(self):
        calls = []

        async def predict(pairs):
            calls.append(pairs)
            return [float(index) for index, _pair in enumerate(pairs)]

        async def scenario():
            batcher = app.InferenceBatcher(
                predict, max_pairs=4, max_wait_ms=20, queue_capacity=8)
            await batcher.start()
            try:
                return await asyncio.gather(
                    batcher.submit("q1", ["a", "b"]),
                    batcher.submit("q2", ["c"]),
                )
            finally:
                await batcher.stop()

        first, second = asyncio.run(scenario())

        self.assertEqual(1, len(calls))
        self.assertEqual(3, len(calls[0]))
        self.assertEqual([0.0, 1.0], first.scores)
        self.assertEqual([2.0], second.scores)
        self.assertEqual(3, first.batch_pairs)
        self.assertEqual(3, second.batch_pairs)


class WarmupTest(unittest.TestCase):
    def test_warmup_runs_a_real_prediction(self):
        original_model = app._model
        original_torch = app._torch
        model = _FakeModel()
        app._model = model
        app._torch = _FakeTorch()
        try:
            app._warm_model()
        finally:
            app._model = original_model
            app._torch = original_torch

        self.assertEqual(1, model.calls)


class ResponseDiagnosticsTest(unittest.TestCase):
    def test_response_contains_stage_latency_fields(self):
        response = app._format_response(
            "Qwen/Qwen3-Reranker-0.6B", [0.9], False,
            queue_wait_ms=3.0, inference_ms=12.0, cache_lookup_ms=0.2,
            total_ms=15.5)

        self.assertEqual(3.0, response["queue_wait_ms"])
        self.assertEqual(12.0, response["inference_ms"])
        self.assertEqual(0.2, response["cache_lookup_ms"])
        self.assertEqual(15.5, response["latency_ms"])


if __name__ == "__main__":
    unittest.main()
