# HBE Development Roadmap

## Foundation Phase (Current) — Pre-Alpha

**Goal:** Repository structure, module boundaries, interfaces, build system.

- [x] Project scaffold and Gradle build system
- [x] 19 module structure with package layout
- [x] Error hierarchy (10 exception types)
- [x] DTOs (BuildRequest, BuildResult, PhaseTiming, etc.)
- [x] Core interfaces (Phase, PhaseContext, BuildContext)
- [x] Infrastructure abstractions (FileSystem, ProcessRunner, NetworkClient)
- [x] Configuration system
- [x] Logging system
- [x] Build graph model and topological sort
- [x] Cache interfaces and placeholder implementation
- [x] Memory management interfaces
- [x] Task scheduler design
- [x] Module interfaces (SdkManager, DependencyManager, etc.)
- [x] Plugin system interfaces
- [x] CLI entry point
- [x] Daemon entry point
- [x] Test infrastructure (InMemoryFileSystem, unit tests)
- [x] Documentation (README, ROADMAP, CONTRIBUTING, CODING_STANDARD, etc.)
- [ ] Compilation verified

## Milestone 1: Core Engine (v0.1.0)

**Goal:** Phase scheduling, pipeline execution, full build lifecycle.

- [ ] Implement full PhaseExecutor with phase-level DAG execution
- [ ] Implement CancellationToken propagation
- [ ] Build graph construction from project detection
- [ ] Configuration file loading (hbe.json, ~/.hbe/config.json)
- [ ] Structured JSON logging output
- [ ] Checkpoint-based recovery stub integration
- [ ] `hbe doctor` with full diagnostics

## Milestone 2: SDK Manager (v0.2.0)

**Goal:** Auto-detect SDK, download missing components, JDK detection.

- [ ] SDK auto-download (platforms, build-tools)
- [ ] License acceptance
- [ ] JDK auto-download (Adoptium API)
- [ ] `hbe downloadSdk` command
- [ ] `hbe doctor` SDK/JDK verification
- [ ] NDK detection

## Milestone 3: Dependency Manager (v0.3.0)

**Goal:** Maven POM resolution, AAR extraction, transitive dependency graph.

- [ ] POM XML parsing
- [ ] Maven repository HTTP fetching
- [ ] Dependency graph construction with nearest-wins
- [ ] AAR extraction (unzip + validation)
- [ ] Conflict resolution
- [ ] `hbe resolveDependencies` command

## Milestone 4: Resource Compiler (v0.4.0)

**Goal:** AAPT2 compile + link, manifest merging, R.java generation.

- [ ] AAPT2 compile invocation
- [ ] AAPT2 link invocation
- [ ] Manifest merging
- [ ] R.java generation
- [ ] Incremental resource compilation

## Milestone 5-6: Source Compiler (v0.5.0)

**Goal:** Java compilation via JDK Compiler API, Kotlin via kotlinc.

- [ ] Java compilation via JDK Compiler API
- [ ] Kotlin compilation via kotlinc process
- [ ] Annotation processing (kapt/KSP)
- [ ] Batch compilation for low RAM
- [ ] Compose compiler plugin integration

## Milestone 7: DEX Engine (v0.6.0)

**Goal:** d8 invocation, multi-dex, R8 for release builds.

- [ ] d8 invocation with class files
- [ ] Multi-dex (method count detection, main dex list)
- [ ] R8 optimization for release builds
- [ ] Chunked dexing for low RAM

## Milestone 8: Packager + Signer (v0.7.0)

**Goal:** Full APK assembly, signing, alignment.

- [ ] ZipBuilder with alignment support
- [ ] APK structure assembly
- [ ] Debug keystore auto-generation
- [ ] apksigner invocation
- [ ] zipalign
- [ ] `adb install` integration

## Milestone 9: Incremental Build (v0.8.0)

**Goal:** Cache system, incremental detection, phase skipping.

- [ ] SQLite cache backend
- [ ] Cache key computation
- [ ] Phase-level incremental detection
- [ ] Change propagation
- [ ] `hbe cache stats/prune/clear`

## Milestone 10: Low-RAM Strategy (v0.9.0)

**Goal:** Build completes on 4GB device without OOM.

- [ ] RAM monitoring and auto-tuning
- [ ] Batched compilation (Java + Kotlin)
- [ ] Chunked DEX
- [ ] Process-based RAM release between phases
- [ ] Memory budget table validation

## v1.0.0 Release

**Goal:** Single-module project builds a valid signed APK.

## v2.0.0 — Scale

- Multi-module projects
- NDK / native code
- Android App Bundle (AAB)
- Product flavors
- Parallel phase execution
- Remote cache (HTTP/S3)

## v3.0.0 — Ecosystem

- Distributed builds
- IDE integration (Android Studio plugin)
- CI/CD integration
- Build analytics dashboard
- Predictive compilation (ML-based)
- Full Gradle DSL parsing
