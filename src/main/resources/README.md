```markdown
# In-Memory Price Service (com.Perry_SP)

Small, in-JVM service for tracking the last price per instrument.

Highlights:
- Producers create batches, upload chunks (<=1000), then complete or cancel.
- When a batch completes, its records are merged into a published snapshot atomically.
- Last price is determined by `asOf`; if two records have equal `asOf`, the later-started batch wins.
- In-memory, optimized for correctness and simple concurrency (lock-free reads).

Build:
- Java 11, Maven
- mvn test
```