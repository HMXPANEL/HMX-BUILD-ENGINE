# HMX Build Engine (HBE) — Architecture Blueprint

## 0. Android APK Build Pipeline (Complete Reference)

### 0.1 Inputs
- Source code (Java/Kotlin)
- Android Resources (`res/`, `assets/`)
- Android Manifest (`AndroidManifest.xml`)
- Native code (`.so` via NDK)
- Dependencies (AAR/JAR from Maven)
- ProGuard/R8 rules
- Build configuration (SDK versions, signing config)

### 0.2 Pipeline Stages (sequential flow)

```
Project Source
    │
    ▼
[1] SDK Resolution ──────► Download missing SDKs/build-tools
    │
    ▼
[2] Dependency Resolution ──► Resolve Maven/Ivy graph → cache AAR/JAR
    │
    ▼
[3] Manifest Merging ──────► Merge multi-module manifests → single AndroidManifest.xml
    │
    ▼
[4] Resource Compilation (AAPT2) ──► Compile res/ → .flat files → link → resources.arsc + R.java
    │
    ▼
[5] Source Compilation ────► javac/kotlinc → .class files
    │
    ▼
[6] Compose Compiler ──────► Kotlin Compose plugin → @Composable → .class
    │
    ▼
[7] DEX Generation (d8) ───► .class files → classes.dex (multiple if needed)
    │
    ▼
[8] ProGuard/R8 ──────────► Optimize/obfuscate/minify dex (optional, release builds)
    │
    ▼
[9] APK Packager ─────────► Zip: dex + resources.arsc + assets/ + lib/ + AndroidManifest.xml
    │
    ▼
[10] APK Signer ──────────► apksigner / jarsigner → .apk
    │
    ▼
[11] zipalign ────────────► 4-byte alignment → release APK
```

### 0.3 APK Internal Structure
```
myapp.apk
├── AndroidManifest.xml          (binary XML)
├── classes.dex                  (DEX bytecode, primary)
├── classes2.dex                 (DEX bytecode, secondary if > 64k methods)
├── classesN.dex
├── resources.arsc               (compiled resource table)
├── res/                         (compiled resources)
│   ├── layout/
│   ├── drawable/
│   ├── mipmap/
│   └── ...
├── assets/                      (raw assets, uncompressed)
├── lib/
│   ├── armeabi-v7a/
│   ├── arm64-v8a/
│   └── x86/
├── META-INF/
│   ├── MANIFEST.MF
│   ├── CERT.RSA
│   └── CERT.SF
├── kotlin/                      (kotlin metadata)
└── stamps/                      (build cache metadata)
```

### 0.4 Key SDK Tools
| Tool | Role | RAM Footprint |
|------|------|--------------|
| `aapt2 compile` | Compile single resources → `.flat` | ~30MB |
| `aapt2 link` | Link `.flat` files → `resources.arsc` + `R.java` | ~100MB |
| `javac` | Java compilation | ~200-500MB |
| `kotlinc` | Kotlin compilation (heavier) | ~300-800MB |
| `d8` | `.class` → `.dex` | ~200-400MB |
| `r8` | Full program optimization | ~300-600MB |
| `apksigner` | APK signing | ~50MB |
| `zipalign` | Byte alignment | ~20MB |

---

## 1. HBE Architecture — Modules & Responsibilities

```
┌──────────────────────────────────────────────────────────┐
│                     API LAYER                            │
│  build() │ clean() │ doctor() │ install() │ download()   │
│  resolveDependencies() │ analyze() │ cache()             │
└────────────────────────┬─────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────┐
│                     CORE ENGINE                          │
│  Pipeline orchestrator │ Phase scheduler │ State machine  │
│  Error recovery │ Lifecycle manager │ Config              │
└────┬─────────┬──────────┬──────────┬──────────┬──────────┘
     │         │          │          │          │
┌────▼──┐ ┌───▼────┐ ┌───▼────┐ ┌───▼────┐ ┌──▼─────┐
│ SDK   │ │ DEP.   │ │ RES.   │ │ SOURCE │ │ DEX    │
│ MGR   │ │ MGR    │ │ COMP.  │ │ COMP.  │ │ ENGINE │
└───────┘ └────────┘ └────────┘ └────────┘ └────────┘
┌───────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│ PKG   │ │ SIGNER │ │ CACHE  │ │ PLUGIN │ │ DIAG   │
│       │ │        │ │        │ │ SYSTEM │ │        │
└───────┘ └────────┘ └────────┘ └────────┘ └────────┘
┌───────┐ ┌────────┐ ┌────────┐
│ MEM   │ │ RECOV- │ │ LOGGER │
│ MGR   │ │ ERY    │ │        │
└───────┘ └────────┘ └────────┘
```

### Module Specifications

#### 1.1 Core Engine
- **File**: `core/`
- **Responsibility**: Pipeline DAG scheduler, phase sequencing, cancellation, error propagation
- **Key behaviors**:
  - Determines which phases to skip (incremental)
  - Runs phases in topological order
  - Exposes `Phase` interface: `execute(context, state) → PhaseResult`
  - Maintains `BuildContext` (shared mutable state, like a build "bag")
  - Parallel phase execution where dependency graph allows
  - Config validation before any execution

#### 1.2 SDK Manager
- **File**: `sdk/`
- **Responsibility**: Auto-download, install, locate Android SDK components
- **Key behaviors**:
  - Detects existing SDK via `ANDROID_HOME`, `ANDROID_SDK_ROOT`
  - Downloads missing SDK platforms, build-tools, cmdline-tools
  - Verifies checksums
  - Tracks SDK versions in `~/.hbe/sdk/`
  - Pin-point version resolution: `compileSdk=34` → downloads `platforms/android-34`
  - NDK detection path
  - JDK detection/download (for devices without JDK)

#### 1.3 Dependency Manager
- **File**: `deps/`
- **Responsibility**: Maven/Gradle dependency resolution, AAR extraction
- **Key behaviors**:
  - Resolves Maven coordinates (`group:artifact:version`)
  - Traverses POM dependency graph (DFS, depth-limited)
  - Conflict resolution: nearest-wins strategy (Gradle-compatible)
  - AAR extraction: unzip → `classes.jar`, `res/`, `AndroidManifest.xml`, `R.txt`, `proguard.txt`
  - Local Maven repo cache at `~/.hbe/cache/maven/`
  - POM parsing (XML → tree)
  - Transitive dependency download
  - Excludes handling

#### 1.4 Resource Compiler
- **File**: `resources/`
- **Responsibility**: AAPT2 compile + link, resource table generation
- **Key behaviors**:
  - Shells out to `aapt2 compile` per resource file
  - Calls `aapt2 link` with all `.flat` files
  - Generates `R.java` for source compilation
  - Handles resource overlays (product flavors, build types)
  - Incremental: re-compile only changed resource files
  - Generates `resources.arsc` with all locales/qualifiers

#### 1.5 Source Compiler
- **File**: `compiler/`
- **Responsibility**: Java/Kotlin compilation, Compose plugin support
- **Key behaviors**:
  - Invokes `javac` and/or `kotlinc` with proper classpath
  - Handles annotation processing (kapt/KSP)
  - Compose compiler plugin: `-Xplugin=/path/to/compose-compiler.jar`
  - Source fileset detection (`.java` and `.kt` files)
  - Outputs `.class` files to build directory
  - Incremental: trace file changes, compile only affected files
  - Batched compilation: chunk sources into compiler invocations to manage RAM

#### 1.6 DEX Engine
- **File**: `dex/`
- **Responsibility**: `.class` → `.dex` via d8, multi-dex, R8 optimization
- **Key behaviors**:
  - Invokes `d8` to convert class files
  - Multi-dex: splits if method count > 64k
  - Main dex list: required classes for cold start
  - R8: full optimization for release builds (proguard rules)
  - Incremental: only re-dex changed class files
  - Memory control: `-Xmx` flags on d8 JVM invocation

#### 1.7 Packager
- **File**: `packager/`
- **Responsibility**: APK assembly, zipalign
- **Key behaviors**:
  - Builds zip archive in APK format
  - Adds: `classes.dex`, `resources.arsc`, `AndroidManifest.xml`, `res/`, `lib/`, `assets/`
  - Compression: `-0` for dex, native libs; `-6` for rest
  - Runs `zipalign -p 4` on output
  - Tracks alignment via zip entry offsets

#### 1.8 Signer
- **File**: `signer/`
- **Responsibility**: APK signing (v1, v2, v3 schemes)
- **Key behaviors**:
  - Supports debug keystore (auto-generated)
  - Supports release keystore (user-provided)
  - Calls `apksigner` or pure-Java signing
  - v2/v3 scheme support (APK Signature Scheme)
  - Signs in-line without repackaging

#### 1.9 Cache
- **File**: `cache/`
- **Responsibility**: Build artifact caching, incremental build support
- **Key behaviors**:
  - Content-addressable cache (SHA-256 of inputs)
  - Caches: compiled resources, `.class` files, `.dex`, merged manifests
  - Cache location: `~/.hbe/cache/build/`
  - LRU eviction policy
  - Max size configurable (default: 2GB)
  - Cache key = hash of (input file paths + timestamps + tool versions)
  - Incremental detection: compare cache keys

#### 1.10 Memory Manager
- **File**: `mem/`
- **Responsibility**: RAM budgeting, GC hints, process management
- **Key behaviors**:
  - Monitors free RAM via `/proc/meminfo` or `ActivityManager`
  - Allocates JVM heap sizes per tool invocation
  - Kills cached processes when RAM is low
  - Tracks peak usage per phase
  - Suggests batch sizes to avoid OOM
  - Throttles parallel phases based on available RAM

#### 1.11 Plugin System
- **File**: `plugin/`
- **Responsibility**: Custom phase injection, tool overrides
- **Key behaviors**:
  - Plugin interface: `HbePlugin { onPhase(ctx, phase) }`
  - Plugins can intercept any phase
  - Classpath-based loading (`ServiceLoader` or custom)
  - Built-in plugins: Compose, NDK, Databinding

#### 1.12 Diagnostics
- **File**: `diag/`
- **Responsibility**: Build health, error reporting, suggestions
- **Key behaviors**:
  - Collects phase timings, RAM usage, cache hit rates
  - Generates structured error output (JSON + human)
  - Suggests fixes for common failures (missing SDK, wrong version, etc.)
  - `doctor()` command: full health check

#### 1.13 Recovery System
- **File**: `recovery/`
- **Responsibility**: Graceful failure, partial build recovery
- **Key behaviors**:
  - Checkpoint state after each phase
  - Resume from last successful checkpoint on crash
  - Corrupted cache detection (checksum validation)
  - Automatic clean + retry (once) on transient failure

#### 1.14 Logger
- **File**: `log/`
- **Responsibility**: Structured logging, multiple outputs
- **Key behaviors**:
  - Log levels: DEBUG, INFO, WARN, ERROR, SILENT
  - Output: stdout (human), file (JSON for AI), socket (for IDE)
  - Formatted with timestamps, phases, durations
  - Machine-parseable for AI consumption

#### 1.15 API Layer
- **File**: `api/`
- **Responsibility**: Public-facing interface for all consumers
- **Key behaviors**:
  - Thin facade over Core Engine
  - All inputs: `BuildRequest` object (serializable)
  - All outputs: `BuildResult` object (status, metrics, error, APK path)
  - Language-agnostic (JSON protocol → stdin/stdout or socket)

---

## 2. API Layer Specification (AI-Facing)

### 2.1 Primary API Surface

```json
// BuildRequest (input)
{
  "command": "build",
  "projectDir": "/path/to/project",
  "variant": "debug",
  "clean": false,
  "incremental": true,
  "minSdk": 24,
  "targetSdk": 34,
  "compileSdk": 34,
  "signing": {
    "type": "debug"
  },
  "mavenRepos": [
    "https://dl.google.com/dl/android/maven2/",
    "https://repo1.maven.org/maven2/"
  ],
  "dependencies": [
    "androidx.appcompat:appcompat:1.6.1"
  ],
  "proguardRules": null,
  "ramBudget": 1024,
  "compose": false
}

// BuildResult (output)
{
  "status": "SUCCESS",
  "apkPath": "/tmp/hbe/build/app-debug.apk",
  "apkSize": 4523124,
  "phases": [
    {"name": "sdk_resolve", "duration": 1200, "ramPeak": 64},
    {"name": "dep_resolve", "duration": 3400, "ramPeak": 128},
    {"name": "res_compile", "duration": 2200, "ramPeak": 96},
    {"name": "source_compile", "duration": 18500, "ramPeak": 512},
    {"name": "dex", "duration": 8100, "ramPeak": 384},
    {"name": "package", "duration": 900, "ramPeak": 128},
    {"name": "sign", "duration": 600, "ramPeak": 64}
  ],
  "totalDuration": 34900,
  "ramPeak": 512,
  "cacheHits": 3,
  "cacheMisses": 4,
  "error": null
}
```

### 2.2 Commands

| Command | Behavior |
|---------|----------|
| `build(project)` | Full APK build |
| `clean(project)` | Delete build artifacts |
| `doctor()` | Health check → report |
| `install(apkPath, deviceId?)` | ADB install |
| `downloadSdk(version)` | Install specific SDK platform |
| `resolveDependencies(project)` | Pre-download all deps, no build |
| `analyze(project)` | Parse project, list modules/versions |
| `cache()` | Cache status, stats, eviction |

### 2.3 AI Integration Protocol

```json
// JSON-over-stdin/stdout subprocess or JSON-over-localhost-socket
// The AI launches:
hbe --json  # starts in JSON-RPC mode
hbe build /path/to/project  # CLI mode

// Result is always a single JSON line (or error stream)
```

---

## 3. Low-RAM Build Strategy (4GB Target)

### 3.1 The Constraint

On a 4GB Android phone, after OS overhead (~1.5-2GB), available RAM is ~2-2.5GB. The Java/Kotlin compiler, d8, and aapt2 are themselves JVM processes that greedily consume heap.

### 3.2 Strategy: Process Isolation + Batching

**Phase 1: Sequential Execution (no parallel phases)**
- Run phases one-at-a-time
- Only one heavy JVM tool is alive at any moment
- Between phases, the previous JVM is killed → RAM released

**Phase 2: Batched Source Compilation**
- Split source files into batches of ~50 files
- Compile each batch in a separate `javac`/`kotlinc` invocation
- After each batch, kill JVM → release RAM
- Trade-off: ~10-20% slower total compile time, but RAM capped at ~600MB

**Phase 3: Streaming D8**
- d8 can handle incrementally fed class files
- Use `--file-per-class` or manual chunking
- Process 100-200 class files per d8 invocation

**Phase 4: Memory Budget Table**

| Phase | RAM Budget | Strategy |
|-------|-----------|----------|
| SDK Resolve | 64 MB | Lightweight HTTP |
| Dep Resolve | 128 MB | Stream POM, don't load all |
| AAPT2 compile | 96 MB | Single-file compile |
| AAPT2 link | 256 MB | One shot, moderate |
| javac (batched) | 256 MB | 50 files/batch |
| kotlinc (batched) | 384 MB | 30 files/batch, -J-Xmx256m |
| d8 (streamed) | 256 MB | Chunked class files |
| R8 | 512 MB | Full heap, only release |
| Packaging | 128 MB | Zip streaming |
| Signing | 64 MB | mmap-based |

### 3.3 Cache Strategy on Storage
- **Cache-on-disk** after every phase
- Content-addressable: `sha256(inputs) → artifact`
- **Cache hit = full phase skip** (no RAM used)
- LRU max 2GB on disk (configurable)
- Clean old cache entries when storage < 500MB free
- Compile cache: `.class` files hashed by source + classpath
- Dex cache: `.dex` files hashed by class file content
- Resource cache: `.flat` files hashed by resource content

### 3.4 Incremental Build Detection
- File timestamp + content hash tracking
- Per-file granularity (not per-module)
- Hash stored in `~/.hbe/cache/build/<project>/filelist.json`
- Changed files → recompile only that batch
- Unchanged files → use cached `.class` / `.dex`

### 3.5 Large Project Handling
- Detect method count early → plan multi-dex upfront
- Batch source compilation across all modules
- Dependency graph traversal in-memory only (POMs not kept resident)
- Stream AAR extraction (unzip to tmp, process, close)
- No AST in memory — tool-based compilation only

### 3.6 RAM Release Patterns
```
// Every phase boundary:
Process.kill(previousProcess)
System.gc() // hint
Thread.sleep(100) // let GC settle
Runtime.freeMemory() // log
```

---

## 4. Development Roadmap

### Milestone 0: Project Scaffold & Configuration
**Goal**: Repository structure, module boundaries, build system for HBE itself
**Modules**: Root project structure, build config, CI
**Expected output**: Empty project compiling, README architecture doc
**Tests**: None (infrastructure)
**Risks**: None
**Dependencies**: None
**Success criteria**: `mvn package` or equivalent produces runnable jar

---

### Milestone 1: Core Engine + Lifecycle
**Goal**: Phase scheduling, state machine, error propagation
**Modules**: `core/`, `log/`, `config/`
**Expected output**: A "hello world" pipeline runs: Phase A → Phase B with context passing
**Tests**: Phase scheduling unit tests, error propagation tests
**Risks**: Over-design of phase system
**Dependencies**: Milestone 0
**Success criteria**: A 3-phase DAG executes in order with state passing

---

### Milestone 2: SDK Manager
**Goal**: Auto-detect, download, install SDK components
**Modules**: `sdk/`, `cache/` (basic)
**Expected output**: `hbe doctor` detects missing SDK, downloads platform 34
**Tests**: SDK detection, download resume, checksum verification
**Risks**: Legal/license acceptance for SDK downloads
**Dependencies**: Milestone 1
**Success criteria**: `doctor()` → report with SDK status

---

### Milestone 3: Dependency Manager
**Goal**: Maven pom resolution, AAR caching
**Modules**: `deps/`, `cache/` (maven repo)
**Expected output**: Resolve `androidx.appcompat:appcompat:1.6.1` → download + extract AAR
**Tests**: Graph resolution, conflict resolution, network failure handling
**Risks**: POM parsing edge cases, large dependency trees (1000+ deps)
**Dependencies**: Milestone 2
**Success criteria**: Single dependency resolved and extracted

---

### Milestone 4: Manifest Processor + Resource Compiler
**Goal**: Manifest merging, AAPT2 compile + link
**Modules**: `resources/`
**Expected output**: Minimal project with only resources produces `resources.arsc` + `R.java`
**Tests**: Manifest merge, resource overlay, qualifier handling
**Risks**: AAPT2 binary incompatibility across platforms
**Dependencies**: Milestone 2 (SDK for aapt2)
**Success criteria**: `R.java` generated, `resources.arsc` produced

---

### Milestone 5: Source Compiler (Java)
**Goal**: Java source compilation with classpath
**Modules**: `compiler/` (Java only)
**Expected output**: `.java` → `.class` with Android SDK classes + dependency jars
**Tests**: Simple Java source compilation, error reporting
**Risks**: javac version compatibility, annotation processing
**Dependencies**: Milestone 3, Milestone 4 (for R.java)
**Success criteria**: `HelloWorld.java` produces `HelloWorld.class`

---

### Milestone 6: Source Compiler (Kotlin + Compose)
**Goal**: Kotlin compilation, Compose compiler plugin
**Modules**: `compiler/` (Kotlin)
**Expected output**: `.kt` → `.class`, `@Composable` functions compile
**Tests**: Kotlin source, Compose annotation, KSP annotation processing
**Risks**: Kotlin compiler version compatibility, Compose compiler plugin path
**Dependencies**: Milestone 5
**Success criteria**: Compose `@Preview` compiles without error

---

### Milestone 7: DEX Engine
**Goal**: d8 invocation, multi-dex, basic dex optimization
**Modules**: `dex/`
**Expected output**: `.class` files → `classes.dex` (single and multi-dex)
**Tests**: Small app dexes, 64k method splitting, d8 error handling
**Risks**: d8 flags compatibility, multi-dex config extraction
**Dependencies**: Milestone 6
**Success criteria**: Single-class app produces valid `classes.dex`

---

### Milestone 8: Packager + Signer
**Goal**: APK assembly, debug signing, alignment
**Modules**: `packager/`, `signer/`
**Expected output**: Run full pipeline → valid signed APK
**Tests**: APK structure validation, install on device, alignment verification
**Risks**: Zip format edge cases, alignment issues
**Dependencies**: Milestones 4, 7
**Success criteria**: `adb install app.apk` succeeds

---

### Milestone 9: Incremental Build + Cache
**Goal**: Cache system, incremental detection, phase skipping
**Modules**: `cache/` (full)
**Expected output**: Second build is ~80% faster than first; changed files recompiled only
**Tests**: Cache hit/miss, incremental correctness, cache corruption recovery
**Risks**: Cache invalidation edge cases (tool version change, classpath change)
**Dependencies**: Milestones 1-8
**Success criteria**: Second build skips all phases for unchanged project

---

### Milestone 10: Low-RAM Strategy
**Goal**: Batched compilation, memory budgeting, process isolation
**Modules**: `mem/`
**Expected output**: Full build completes on 4GB device without OOM
**Tests**: RAM-limited build (cgroup or ulimit), performance measurement
**Risks**: Multi-batch correctness, edge cases with interdependent files
**Dependencies**: Milestone 9
**Success criteria**: Build completes with `ulimit -v 2500000` (2.5GB)

---

### Milestone 11: R8 / ProGuard
**Goal**: Release build optimization, obfuscation, minification
**Modules**: `dex/` (extend for R8)
**Expected output**: Release APK with minified dex, proguard mapping file
**Tests**: R8 rules processing, class retention, mapping output
**Risks**: R8 compatibility with Compose, keep rules extraction from AARs
**Dependencies**: Milestone 8, Milestone 10
**Success criteria**: Release APK installs and runs with R8

---

### Milestone 12: Plugin System
**Goal**: Plugin loading, phase interception
**Modules**: `plugin/`
**Expected output**: External plugin modifies a phase (e.g., custom resource compiler)
**Tests**: Plugin load, plugin override, plugin failure isolation
**Risks**: Classloader issues, dependency conflicts
**Dependencies**: Milestone 1
**Success criteria**: Plugin counts phase executions

---

### Milestone 13: Diagnostics + Recovery
**Goal**: Error reporting, crash recovery, health checks
**Modules**: `diag/`, `recovery/`
**Expected output**: Build crash → resume from last checkpoint; `doctor()` → full report
**Tests**: Crash recovery, checkpoint corruption, partial build resume
**Risks**: Checkpoint I/O overhead, state serialization correctness
**Dependencies**: Milestone 12
**Success criteria**: Mid-build crash resumes without re-doing completed phases

---

### Milestone 14: AI Protocol + JSON-RPC
**Goal**: JSON transport mode, structured output
**Modules**: `api/`
**Expected output**: `echo '{"command":"build"}' | hbe --json` → JSON result
**Tests**: All six API commands via JSON protocol
**Risks**: Streaming issues, large output buffering
**Dependencies**: Milestone 13
**Success criteria**: All API commands work bidirectionally via JSON

---

### Milestone 15: NDK / Native Support
**Goal**: NDK detection, `ndk-build` or `cmake` integration
**Modules**: `sdk/` (extend), `compiler/` (native)
**Expected output**: APK with native `.so` libraries
**Tests**: NDK build, ABI filtering, `.so` inclusion in APK
**Risks**: NDK version compatibility, cross-compilation complexity
**Dependencies**: Milestone 14
**Success criteria**: App with native lib installs and runs

---

### Milestone 16: Multi-Module Projects
**Goal**: Library modules, app module, multi-module dependency graph
**Modules**: `core/` (extend), `deps/` (extend)
**Expected output**: `:library` → AAR; `:app` → APK depending on library AAR
**Tests**: Inter-module dependency, AAR consumption, R.java merging
**Risks**: Circular dependencies, resource ID conflicts
**Dependencies**: Milestone 14
**Success criteria**: Multi-module project builds and runs

---

### Milestone 17: Performance Optimization
**Goal**: Parallel phase execution (where RAM permits), build speed tuning
**Modules**: `mem/`, `core/`
**Expected output**: Build is 2-3x faster on high-RAM devices, same speed on low-RAM
**Tests**: Performance benchmarks across RAM budgets (2GB, 4GB, 8GB)
**Risks**: Parallel correctness, race conditions in cache
**Dependencies**: Milestone 16
**Success criteria**: Build time scales with available RAM

---

## 5. Critical Design Review

### 5.1 Weak Points

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **Process spawning overhead** | Each tool invocation (javac, d8) is a new JVM → 1-3s startup cost. Batched compilation multiplies this. | Keep process warm? Hard with JVM. Accept cost; batch sizes tuned to minimize overhead. |
| **AAPT2 binary dependency** | Must ship or detect platform-specific aapt2 binary. ARM vs x86_64 vs aarch64. | Bundle aapt2 for common architectures; fallback to SDK download. |
| **Kotlin compiler memory** | kotlinc is a memory hog (~800MB for medium projects). Without GraalVM native-image, this is hard. | Strict batching + `-J-Xmx256m`. Compose compiler plugin adds overhead. |
| **Maven resolution complexity** | POM parsing is XML-heavy; transitive graph can be 500+ nodes. | Flat depth-first traversal; no in-memory graph kept after resolution. |
| **Signing key security** | Keystore must be stored securely on device. Android Keystore API not accessible from JVM. | Encrypt with device-bound key; prompt user on first release build. |

### 5.2 Performance Bottlenecks

1. **Kotlin compilation** is the #1 bottleneck (>50% of build time)
   - Mitigation: Max batching, incremental compilation, kotlin daemon reuse (if available)
2. **D8 for large projects** (>10k classes)
   - Mitigation: Chunked dexing, parallel dex partitions
3. **AAPT2 link** with many configurations (>20 locales, >5 screen densities)
   - Mitigation: Filter configurations not needed; cache intermediate `.flat`

### 5.3 Compatibility Issues

| Concern | Assessment |
|---------|-----------|
| **Android 5.x (API 21) support** | aapt2 supports; d8 supports; no issues |
| **Android 14+ (API 34) features** | Compose compiler plugin must match Kotlin version |
| **Java 17+ features** | d8 supports Java 17 bytecode as of 8.0+ |
| **Non-Gradle projects** | No build.gradle parsing needed — HBE uses its own config |
| **Custom lint checks** | Not needed for build; lint is a separate tool |
| **Databinding/ViewBinding** | Requires annotation processing → KSP/kapt support in Milestone 6 |

### 5.4 Security Concerns

1. **Arbitrary Maven dependency downloads** → supply chain attack
   - Mitigation: Checksum verification, allow-list for repos, GPG signature verification (optional)
2. **Arbitrary build execution** → malicious build scripts
   - Mitigation: HBE uses declarative config, not build scripts. No arbitrary code execution in config.
3. **SDK binary integrity** → compromised SDK
   - Mitigation: Match checksums against Google's published hashes
4. **Keystore exfiltration** → signed malware
   - Mitigation: Encrypted keystore storage, biometric unlock option

### 5.5 Future Scalability Problems

1. **Monorepo support** (100+ modules)
   - Challenge: Dependency graph resolution in memory, POM scanning
   - Plan: Lazy module loading, on-demand resolution
2. **Remote caching** (shared cache across machines)
   - Plan: Cache backend abstraction (local fs → HTTP → S3)
3. **Distributed builds**
   - Plan: Phase execution can be RPC'd; each phase is a self-contained unit
4. **Android Gradle Plugin (AGP) compat**
   - Challenge: AGP generates AAPT2 flags, manifest placeholders, build config fields
   - Plan: HBE config replaces AGP; no compatibility layer needed (greenfield)

### 5.6 Suggestions for Improvement

1. **Use JDK's built-in compiler API** instead of shelling out to `javac` — reduces process overhead, enables stream compilation. Kotlin still requires process spawn.

2. **Adopt a DB-backed cache** (SQLite, not file-hash-indexed) for scalable artifact lookups. SQLite handles millions of entries efficiently where filesystem directory scans don't.

3. **Pre-compute dex layout** — analyze method references before dexing to plan optimal class-to-dex partitioning, reducing d8 passes.

4. **Build a "warm-up" mode** — `hbe prepare` that downloads SDKs, resolves deps, and compiles R.java without full build. The actual `hbe build` then starts from source compilation.

5. **Support Gradle project migration** — read `build.gradle.kts` (limited) to auto-generate HBE config. This is critical for adoption; nobody wants to manually rewrite build config.

6. **Android Virtual Device (AVD) detection** for `install()` — automatically find running emulator.

---

## 6. Key Design Decisions Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | **Kotlin** + **Java** interop | Kotlin for engine code (concise, safe, coroutines for async), Java for compiler API |
| Transport | **JSON-RPC over stdin/stdout** | Universal, no socket binding issues on Android, easy for any language consumer |
| Config format | **HBE-specific JSON** (not build.gradle) | Avoid Gradle compatibility complexity; clean slate |
| Cache granularity | **Per-file content hash** | Maximum cache hits, minimal recomputation |
| Parallelism | **Phase-level only** (not file-level) | Simpler correctness; RAM constraints make file-level parallelism dangerous on 4GB |
| Dependency resolution | **Nearest-wins** | Gradle-compatible; avoids version conflict complexity |
| AAR extraction | **On-demand, cached** | Extract only when needed; cache extracted dir |
| Incremental detection | **Hash-based, not timestamp-only** | Timestamps can be unreliable on Android filesystems (FUSE, MTP) |
| Plugin system | **ServiceLoader-based** | Standard Java, no framework dependency |
| Error recovery | **Phase-level checkpoint** | Resume from last completed phase; serialize state as JSON |
| Signing | **apksigner wrapper** | Avoids reimplementing complex signing schemes (v2/v3) |
| JVM tools | **Process spawn** (not in-process) | Isolation — OOM in javac ≠ crash HBE itself; cleaner memory release |
