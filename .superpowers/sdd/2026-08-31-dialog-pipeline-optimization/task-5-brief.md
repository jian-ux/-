# Task 5 brief

Implement the processInternal decomposition from the dialog pipeline plan with the smallest behavior-preserving change.

- Add `DialogContextLoader`, `DialogRetrievalCoordinator`, and `DialogResponsePostProcessor` under the core service package.
- Each component must expose a clear typed API and immutable result where practical; preserve existing `DialogServiceImpl.send*` signatures, response keys, branch order, safety gates, transfer behavior and saveReplyLog inputs.
- Integrate at least one real stage into `DialogServiceImpl` without duplicating the full pipeline or adding keyword intent routing. It is acceptable to wrap existing collaborators and delegate unchanged logic while keeping compatibility.
- Add focused tests proving stage delegation and existing behavior for greeting, history recall, FAQ/RAG, no-answer, safety and handoff paths. Keep changes scoped and run the relevant core tests.
