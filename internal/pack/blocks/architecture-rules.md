- Keep high-level policy independent of IO, UI, framework, filesystem,
  database, network, and device details.
- Make low-level adapters depend inward on stable high-level concepts.
- Split modules that mix unrelated responsibilities or leak implementation
  details across boundaries.
- Prefer narrow interfaces and private representation.
