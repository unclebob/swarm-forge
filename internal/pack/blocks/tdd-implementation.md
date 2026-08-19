- Use TDD to specify behavior before implementation. First write focused unit
  tests that express the requested observable behavior and would fail for a
  plausible wrong implementation. Then write only enough production code to
  pass those tests and clean touched code locally.
- Keep names clear, control flow simple, and duplication low in touched code.
- Do not perform broad cleanup unless it blocks the behavior slice.
