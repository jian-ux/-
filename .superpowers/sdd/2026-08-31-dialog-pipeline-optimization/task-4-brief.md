# Task 4 brief

Implement task 4 from the dialog pipeline optimization plan.

- Add request-scoped `RedactionMemoizer.redact(String, Set<String>)`; preserve current masking semantics and never leak values across requests.
- Add `DialogResponseMetadata` that builds shared diagnostic metadata once and can be reused for response, message metadata and AI reply log without changing existing JSON keys.
- Integrate only the highest-duplication call sites in `DialogServiceImpl`; avoid unrelated behavior changes and no keyword intent rules.
- Add focused tests for repeated redaction, accumulated redaction types, no cross-request cache leak, and response-key compatibility.
- Keep existing public APIs and response fields backward compatible.
- Run the focused tests and relevant `DialogServiceImplTest` subset.
