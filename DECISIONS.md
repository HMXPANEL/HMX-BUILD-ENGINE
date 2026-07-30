# Architecture Decision Records

## ADR-001: Language Choice

**Decision:** Kotlin + Java interop  
**Context:** Engine needs to invoke JDK Compiler API (Java) and build Android APKs.  
**Rationale:** Kotlin for engine logic (coroutines, null safety); Java for JDK Compiler API.  
**Status:** Accepted

## ADR-002: Transport Protocol

**Decision:** JSON-RPC over stdin/stdout  
**Context:** AI agents, CLI, and Android apps need to communicate with HBE.  
**Rationale:** Universal, no port conflicts, works on Android without socket permissions, simple parsing.  
**Status:** Accepted

## ADR-003: Build Configuration Format

**Decision:** HBE-specific JSON (`hbe.json`)  
**Context:** HBE needs a declarative build configuration.  
**Rationale:** Avoid Gradle DSL parsing complexity; machine-friendly format; clean slate without legacy.  
**Alternative considered:** build.gradle.kts parsing (rejected due to complexity).  
**Status:** Accepted

## ADR-004: Cache Backend

**Decision:** SQLite → pluggable  
**Context:** Cache needs efficient metadata queries across millions of entries.  
**Rationale:** SQLite handles indexed lookups efficiently; filesystem-only cache degrades with many entries.  
**Status:** Deferred (filesystem cache in v1, SQLite in v2)

## ADR-005: Process Model

**Decision:** Multi-process (spawn SDK tools)  
**Context:** Tools like javac, kotlinc, d8, aapt2 may crash or OOM.  
**Rationale:** Process isolation prevents tool crash from taking down engine; clean RAM release via process death.  
**Alternative considered:** In-process JVM API (rejected: no safe in-process API for kotlinc/d8).  
**Status:** Accepted

## ADR-006: Dependency Resolution

**Decision:** Nearest-wins conflict resolution  
**Context:** Maven dependency graph can have conflicting versions.  
**Rationale:** Gradle-compatible; deterministic; simple to implement.  
**Status:** Accepted

## ADR-007: Parallelism

**Decision:** Phase-level parallelism only  
**Context:** RAM is constrained (4GB target).  
**Rationale:** File-level parallelism would multiply RAM usage; phase-level is safer and simpler.  
**Status:** Accepted (can be revisited for high-RAM devices)

## ADR-008: Cache Keys

**Decision:** Content-hash-based (SHA-256)  
**Context:** Cache invalidation must be reliable across filesystem types.  
**Rationale:** File timestamps are unreliable on Android FUSE/MTP filesystems.  
**Status:** Accepted

## ADR-009: Recovery

**Decision:** Phase-level checkpoint  
**Context:** Long builds should survive crashes.  
**Rationale:** Resume from last completed phase; simpler than per-file checkpointing.  
**Status:** Accepted

## ADR-010: Signing

**Decision:** apksigner wrapper  
**Context:** APK signing (v2/v3) is cryptographically complex.  
**Rationale:** Delegating to apksigner avoids reimplementing complex signing schemes.  
**Status:** Accepted

## ADR-011: Dependency Injection

**Decision:** Manual constructor injection (no DI framework)  
**Context:** HBE has a manageable number of modules.  
**Rationale:** No framework overhead; explicit wiring; easy to trace dependencies.  
**Status:** Accepted

## ADR-012: Module Granularity

**Decision:** 19 fine-grained modules  
**Context:** Each build phase is a potential independent unit.  
**Rationale:** Clear separation of concerns; each module testable in isolation.  
**Status:** Accepted
