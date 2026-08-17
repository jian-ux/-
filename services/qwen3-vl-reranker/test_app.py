import unittest

import app


class _FakeModel:
    def __init__(self):
        self.calls = 0

    def predict(self, pairs, **_kwargs):
        self.calls += 1
        return [0.75 - index * 0.1 for index, _pair in enumerate(pairs)]


class _FakeNn:
    @staticmethod
    def Sigmoid():
        return object()


class _FakeTorch:
    nn = _FakeNn()


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
        second_scores, second_cache_hit = app._predict("same query", documents)
        changed_scores, changed_cache_hit = app._predict(
            "same query", ["document one", "changed document"])

        self.assertEqual(first_scores, second_scores)
        self.assertEqual(first_scores, changed_scores)
        self.assertFalse(first_cache_hit)
        self.assertTrue(second_cache_hit)
        self.assertFalse(changed_cache_hit)
        self.assertEqual(2, self.model.calls)


if __name__ == "__main__":
    unittest.main()
