# HMX Build Engine — Technical Specification v1.0

**Status:** Draft  
**Author:** HBE Architecture Team  
**Version:** 1.0.0  
**Last Updated:** 2026-07-30

---

## Table of Contents

1. System Design
2. Internal Architecture
3. Module Interfaces
4. Public API Contracts
5. Internal Data Structures
6. Build Graph Model
7. Task Scheduler Design
8. Dependency Resolution Algorithm
9. SDK Management Algorithm
10. Resource Compilation Workflow
11. Java Compilation Workflow
12. Kotlin Compilation Workflow
13. Compose Compilation Workflow
14. DEX Generation Workflow
15. APK Packaging Workflow
16. APK Signing Workflow
17. Incremental Build Algorithm
18. Cache Architecture
19. Cache Invalidation Algorithm
20. Memory Management Strategy
21. Low RAM Optimization Strategy
22. Project Detection Algorithm
23. Android Studio Compatibility Layer
24. AndroidIDE Compatibility Layer
25. HBE Project Format
26. Configuration Format and JSON Schemas
27. Plugin SDK Specification
28. AI Integration Protocol
29. JSON-RPC Protocol
30. CLI Specification
31. Daemon Architecture
32. Logging Architecture
33. Diagnostics System
34. Recovery System
35. Security Architecture
36. Build Database Design
37. Artifact Management
38. File System Abstraction
39. Network Layer
40. Testing Architecture
41. Benchmark Strategy
42. Performance Optimization Strategy
43. Error Handling Strategy
44. Versioning Strategy
45. Migration Strategy from Gradle Projects
46. Future Roadmap (v1, v2, v3)

---

## 1. System Design

### 1.1 Purpose

HBE is a standalone, embeddable Android APK build engine designed to run on low-end hardware (4GB RAM target) without requiring Gradle, Android Studio, or any IDE. It compiles Java/Kotlin source code, Android resources, and dependencies into a signed, aligned APK through a modular pipeline of isolated phases.

### 1.2 Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Runtime | **JVM (Java 17+)** | Kotlin compiler and d8 require JVM; GraalVM native-image not viable due to dynamic class loading in SDK tools |
| Language | **Kotlin** with Java interop | Kotlin for engine logic (coroutines, null safety); JDK Compiler API for Java compilation |
| Build definition | **HBE JSON config** (not build.gradle) | Avoid Gradle AST parsing; declarative config is machine-friendly |
| Process model | **Multi-process** (spawn SDK tools) | OOM isolation; clean RAM release between phases |
| Transport | **JSON-RPC over stdin/stdout** | Universal, no port conflicts, works on Android without sockets |
| Cache backend | **SQLite** (initial) → pluggable | Efficient for millions of entries; indexed queries beat filesystem scans |
| Dependency resolution | **Nearest-wins** (Gradle compatible) | Established algorithm; deterministic |

### 1.3 Alternatives Considered

| Alternative | Rejected Because |
|-------------|-----------------|
| GraalVM native-image | Cannot dynamically invoke kotlinc/d8/aapt2 — these tools require JVM classloading |
| Go/Rust implementation | Cannot directly use Android SDK Java tools without FFI bridge; adds complexity without benefit |
| In-process JVM compiler API for everything | kotlinc and d8 have no safe in-process API; crash in compiler = crash in engine |
| Gradle wrapper reuse | Gradle is too heavy for 4GB target; defeats purpose of lightweight engine |
| Socket-based transport | Requires port management, firewall issues on Android, complicates containerization |

### 1.4 Trade-offs

1. **Process spawn overhead vs RAM safety** — Starting JVM per batch costs ~1-3s but guarantees OOM isolation. Acceptable for low-RAM target.
2. **JSON config vs Gradle compatibility** — New format means migration cost, but avoids inheriting Gradle's DSL complexity and version incompatibility.
3. **SQLite cache vs flat file store** — SQLite adds ~2MB dependency but enables efficient queries (e.g., "all cache entries for project X created after timestamp Y").
4. **Phase-level parallelism vs simplicity** — No file-level parallelism simplifies correctness but leaves throughput on the table for high-RAM devices.

### 1.5 Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| SDK tool version incompatibility | Medium | Pin tested tool versions; automated integration tests per version |
| Kotlin compiler API breaking changes | Medium | Isolate kotlinc invocation behind `CompilerAdapter` interface |
| Android platform API changes (resources) | Low | aapt2 maintained by Google; follow semver |
| 4GB insufficient for complex projects | Low | Batching + incremental; if still OOM, spill to disk-based compilation |
| Network unreachable for SDK/dep download | Medium | Offline mode; cache-aside pattern; retry with backoff |

### 1.6 Future Improvements

- **Warm daemon** for kotlinc (reuse compiler process across builds) — reduces batch overhead by ~60%
- **GraalVM-based tool bundling** — once kotlinc/d8 support native-image, run them in-process
- **WebAssembly target** — for browser-based HBE consumers

---

## 2. Internal Architecture

### 2.1 Layer Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                      CONSUMER LAYER                          │
│   AI Agent  │  CLI  │  Android App  │  IDE Plugin  │  OS     │
└──────────────────────────┬───────────────────────────────────┘
                           │ JSON-RPC (stdin/stdout or socket)
┌──────────────────────────▼───────────────────────────────────┐
│                      API LAYER (api/)                        │
│   BuildRequest / BuildResult serialization                   │
│   Command dispatcher: build, clean, doctor, install, ...      │
│   Subprocess launcher (CLI mode)                             │
└──────────────────────┬───────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                  CORE ENGINE (core/)                         │
│  BuildGraphBuilder │ TaskScheduler │ PhaseExecutor            │
│  ConfigValidator │ BuildContext │ LifecycleManager            │
│  PluginLoader │ CancellationToken                             │
└───┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬────┬───┘
    │    │    │    │    │    │    │    │    │    │    │    │
┌───▼┐ ┌▼───┐┌▼───┐┌▼───┐┌▼───┐┌▼───┐┌▼───┐┌▼───┐┌▼───┐┌▼───┐┌▼───┐┌▼───┐
│SDK │ │DEP │ │RES │ │SRC │ │DEX │ │PKG │ │SIGN│ │CCH │ │MEM │ │RECV│ │LOG │ │DIAG│
│MGR │ │MGR │ │CMP │ │CMP │ │ENG │ │    │ │    │ │    │ │MGR │ │    │ │    │ │    │
└────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘
┌──────────────────────────────────────────────────────────────┐
│                    INFRASTRUCTURE LAYER                       │
│  FileSystem (abstracted) │ Network (HTTP/S) │ ProcessRunner  │
│  Crypto (hash, sign) │ ZipBuilder │ TempFileManager           │
│  SQLite (cache DB) │ Scheduler (thread pool)                  │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 Layer Responsibilities

**Consumer Layer** — Any system that invokes HBE. Communicates exclusively through the API layer.

**API Layer** — Serialization boundary. Converts JSON-RPC to internal `BuildRequest` objects. Returns `BuildResult`. No build logic.

**Core Engine** — Orchestrates the build. Builds the task graph, schedules phases, handles cancellation and error propagation. Owns the build lifecycle.

**Module Layer** — Independent build phases. Each module has a single responsibility, defined interfaces, and no shared mutable state with other modules (state passes through `BuildContext`).

**Infrastructure Layer** — Shared utilities. File I/O, networking, process management, crypto, compression. Abstracted behind interfaces so they can be mocked or replaced.

### 2.3 Dependency Injection

Modules receive dependencies through constructor injection:

```
class ResourceCompiler(
    private val sdkManager: SdkManager,
    private val cacheManager: CacheManager,
    private val fileSystem: FileSystem,
    private val processRunner: ProcessRunner
)
```

No DI framework. Manual wiring in `Main.kt` / `HbeEngineFactory`.

---

## 3. Module Interfaces

### 3.1 Phase Interface
</parameter>

### 3.1 Phase Interface

Every build step implements the Phase interface:

```java
public interface Phase {
    String getName();
    PhaseResult execute(PhaseContext ctx, PhaseState state);
    List<Class<? extends Phase>> getDependencies();
    boolean isSkippable(PhaseContext ctx, PhaseState state);
    long estimateMemoryMb();
}

public class PhaseResult {
    public enum Status { SUCCESS, SKIPPED, FAILED, CANCELLED }
    private final Status status;
    private final long durationMs;
    private final long memoryPeakBytes;
    private final PhaseError error;  // null on success
}

public class PhaseContext {
    BuildContext getBuildContext();
    FileSystem getFileSystem();
    ProcessRunner getProcessRunner();
    Logger getLogger();
    CacheManager getCacheManager();
    MemoryMonitor getMemoryMonitor();
    CancellationToken getCancellationToken();
}

public class PhaseState {
    // Checkpoint-serializable state bag
    void set(String key, Object value);
    <T> T get(String key, Class<T> type);
    Map<String, Object> snapshot();
    void restore(Map<String, Object> snapshot);
}
```

### 3.2 Module Interfaces Summary

| Module | Interface | Key Methods |
|--------|-----------|-------------|
| SdkManager | `SdkManager` | `resolveSdk(compileSdk, buildToolsVersion)` → `SdkResolution`; `doctor()` → `SdkDiagnosis`; `downloadPlatform(apiLevel)`; `downloadBuildTools(version)`; `getSdkPath()`; `getJdkPath()` |
| DependencyManager | `DependencyManager` | `resolve(Set<MavenCoordinate>, List<Repository>)` → `DependencyGraph`; `extractAar(Artifact)` → `AarContents`; `findTransitiveDependencies(Node)` → `Set<Edge>` |
| ResourceCompiler | `ResourceCompiler` | `compile(ResFile)` → `FlatFile`; `link(List<FlatFile>, Manifest)` → `ResourceBundle`; `generateRJava(Bundle)` → `SourceFile` |
| SourceCompiler | `SourceCompiler` | `compile(Set<SourceFile>, Classpath)` → `Set<ClassFile>`; `getAnnotationProcessors()` → `Set<Processor>` |
| DexEngine | `DexEngine` | `dex(Set<ClassFile>, DexConfig)` → `DexOutput`; `r8(Set<ClassFile>, ProGuardRules)` → `DexOutput`; `computeMethodCount(Set<ClassFile>)` → `int` |
| Packager | `Packager` | `packageApk(DexOutput, ResourceBundle, NativeLibs, Assets, Manifest)` → `ApkFile`; `zipalign(ApkFile)` → `ApkFile` |
| Signer | `Signer` | `sign(ApkFile, SigningConfig)` → `SignedApk`; `generateDebugKeystore()` → `Keystore`; `verifySignature(ApkFile)` → `SignatureInfo` |
| CacheManager | `CacheManager` | `get(ArtifactKey)` → `Optional<Artifact>`; `put(ArtifactKey, Artifact)`; `invalidate(ArtifactKey)`; `evict(long maxBytes)`; `cleanup(Instant before)`; `stats()` → `CacheStats` |
| MemoryManager | `MemoryManager` | `getAvailableMemory()` → `long`; `budgetForPhase(Phase)` → `long`; `releaseMemory()`; `isLowMemory()` → `boolean`; `registerPhase(Phase)` |
| RecoverySystem | `RecoverySystem` | `checkpoint(PhaseState)`; `getLastCheckpoint()` → `Optional<PhaseState>`; `clearCheckpoints()`; `isRecoveryAvailable()` → `boolean` |
| Logger | `HbeLogger` | `debug(String, Map<String, Object>)`; `info(String, Map<String, Object>)`; `warn(String, Map<String, Object>)`; `error(String, PhaseError)`; `setOutput(LogOutput)` |
| Diagnostics | `Diagnostics` | `collect()` → `DiagnosticReport`; `doctor()` → `HealthReport`; `suggestFix(PhaseError)` → `String` |
| PluginLoader | `PluginLoader` | `loadPlugins(Path pluginDir)` → `List<HbePlugin>`; `getPluginForPhase(Phase)` → `Optional<HbePlugin>` |

### 3.3 Data Flow Contract

Data flows between modules exclusively through `PhaseState` (in `PhaseContext`). Each phase writes its outputs to state; dependent phases read from it.

```
Phase: SdkResolve
  writes: sdkResolution (SdkResolution)

Phase: DependencyResolve
  reads:  sdkResolution
  writes: dependencyGraph (DependencyGraph), aarContents (Map<Coordinate, AarContents>)

Phase: ResourceCompile
  reads:  sdkResolution, dependencyGraph
  writes: resourceBundle (ResourceBundle), rJava (SourceFile)

Phase: SourceCompile
  reads:  sdkResolution, dependencyGraph, aarContents, resourceBundle, rJava
  writes: classFiles (Set<ClassFile>)

Phase: DexGeneration
  reads:  classFiles, sdkResolution
  writes: dexOutput (DexOutput)

Phase: Package
  reads:  dexOutput, resourceBundle, manifest (Manifest), sdkResolution
  writes: apkFile (ApkFile)

Phase: Sign
  reads:  apkFile, signingConfig (SigningConfig)
  writes: signedApk (SignedApk)
```

No module reads from or writes to global state. All state is scoped to the build session.

---

## 4. Public API Contracts

### 4.1 Top-Level Functions

```java
public final class Hbe {
    /** Full APK build. Returns result synchronously (blocking). */
    public static BuildResult build(BuildRequest request);

    /** Clean build artifacts for the given project. */
    public static CleanResult clean(CleanRequest request);

    /** Health check: SDK, JDK, disk space, network. */
    public static DoctorResult doctor(DoctorRequest request);

    /** ADB install APK on device/emulator. */
    public static InstallResult install(InstallRequest request);

    /** Download specific SDK platform/build-tool. */
    public static DownloadResult downloadSdk(DownloadSdkRequest request);

    /** Pre-resolve (download + extract) all dependencies without compiling. */
    public static ResolveResult resolveDependencies(ResolveRequest request);

    /** Analyze project: detect type, modules, dependencies, SDK versions. */
    public static AnalyzeResult analyze(AnalyzeRequest request);

    /** Cache management: stats, prune, invalidate. */
    public static CacheResult cache(CacheRequest request);

    /** Shutdown any running daemon. */
    public static void shutdown();
}
```

### 4.2 BuildRequest Contract

```java
public class BuildRequest {
    String projectDir;                // required
    String variant;                   // "debug" | "release" (default: "debug")
    boolean clean;                    // full clean before build (default: false)
    boolean incremental;              // use incremental build (default: true)
    Integer minSdk;                   // override AndroidManifest minSdkVersion
    Integer targetSdk;                // override targetSdkVersion
    Integer compileSdk;               // SDK version to compile against
    String buildToolsVersion;         // specific build-tools version (default: latest)
    SigningConfig signingConfig;      // debug auto-generated if null
    List<String> mavenRepos;          // additional Maven repositories
    List<String> dependencies;        // additional Maven coordinates
    String proguardRules;             // path to proguard rules file (release only)
    int ramBudgetMb;                  // max RAM for this build (default: 1024)
    boolean compose;                  // enable Compose compiler plugin (default: false)
    String outputApkPath;             // explicit output path (default: auto)
    boolean daemon;                   // reuse daemon process (default: false)
    Map<String, String> properties;   // custom key/value for plugins
}
```

### 4.3 BuildResult Contract

```java
public class BuildResult {
    Status status;                    // SUCCESS, FAILURE, CANCELLED
    String apkPath;                   // null on failure
    long apkSizeBytes;                // 0 on failure
    List<PhaseTiming> phases;         // per-phase timing/memory
    long totalDurationMs;
    long ramPeakBytes;
    int cacheHits;
    int cacheMisses;
    BuildError error;                 // null on success
    String buildId;                   // unique build identifier (UUID)
    Map<String, Object> metadata;     // extensible
}

public class PhaseTiming {
    String name;
    PhaseResult.Status status;
    long durationMs;
    long ramPeakBytes;
    int inputCount;                   // e.g., number of files compiled
    int cacheHit;                     // 1 if this phase was fully cached
}

public class BuildError {
    String phase;                     // phase where failure occurred
    String code;                      // machine-readable error code
    String message;                   // human-readable
    String suggestion;                // suggested fix
    Throwable cause;                  // original exception
    List<String> details;             // error details (e.g., compiler output lines)
}
```

### 4.4 Error Codes

| Code | Meaning |
|------|---------|
| `SDK_NOT_FOUND` | Required SDK platform not installed and download failed |
| `SDK_DOWNLOAD_FAILED` | SDK download failed (network or checksum) |
| `MAVEN_RESOLVE_FAILED` | Dependency could not be resolved from any repository |
| `MAVEN_DOWNLOAD_FAILED` | Dependency resolved but download failed |
| `AAPT2_COMPILE_ERROR` | Resource file failed aapt2 compilation (syntax error in XML) |
| `AAPT2_LINK_ERROR` | Resource linking failed (conflicting IDs, missing reference) |
| `MANIFEST_MERGE_ERROR` | Manifest merging failed (conflicting attributes) |
| `JAVAC_COMPILE_ERROR` | Java source compilation error |
| `KOTLINC_COMPILE_ERROR` | Kotlin source compilation error |
| `COMPOSE_COMPILE_ERROR` | Compose compiler plugin error |
| `ANNOTATION_PROCESSOR_ERROR` | Annotation processing (kapt/KSP) failed |
| `D8_ERROR` | DEX conversion failed |
| `R8_ERROR` | R8 optimization failed |
| `PACKAGE_ERROR` | APK packaging failed |
| `SIGNING_ERROR` | APK signing failed |
| `INVALID_CONFIG` | Build configuration is invalid |
| `PROJECT_NOT_FOUND` | Project directory does not exist or is not recognized |
| `OUT_OF_MEMORY` | Build exceeded RAM budget |
| `PROCESS_CRASHED` | External tool process crashed unexpectedly |
| `CACHE_CORRUPTION` | Cache data corrupted, recovery attempted |
| `UNSUPPORTED_FEATURE` | Requested feature not yet implemented |
| `INTERNAL_ERROR` | Unexpected engine error |

### 4.5 Error Handling Contract

- Errors are **never silent**. Every error produces a `BuildError` with code, message, and suggestion.
- Non-critical warnings (e.g., cache miss) are logged but do not fail the build.
- The engine **never exits the JVM**. All errors are caught and reported through the result object.
- External process crashes are detected via exit codes and stderr parsing.
- Compiler errors are parsed from output and attached to the `BuildError.details` list.

---

## 5. Internal Data Structures

### 5.1 MavenCoordinate

```java
public class MavenCoordinate {
    String groupId;       // e.g., "androidx.appcompat"
    String artifactId;    // e.g., "appcompat"
    String version;       // e.g., "1.6.1"
    String classifier;    // e.g., "sources" (nullable)
    String extension;     // e.g., "aar", "jar" (default: "jar")

    // Derived
    String toPath();      // "androidx/appcompat/appcompat/1.6.1/appcompat-1.6.1.aar"
    String toNotation();  // "androidx.appcompat:appcompat:1.6.1"
}
```

### 5.2 DependencyGraph

```java
public class DependencyGraph {
    List<DependencyNode> roots;

    public static class DependencyNode {
        MavenCoordinate coordinate;
        List<DependencyEdge> dependencies;  // outgoing edges
        String scope;                       // "compile", "runtime", "provided"
        boolean optional;
        List<MavenCoordinate> excludes;     // excluded transitive deps
    }

    public static class DependencyEdge {
        DependencyNode target;
        String scope;
    }
}
```

### 5.3 BuildGraph

```java
public class BuildGraph {
    List<BuildNode> nodes;
    List<BuildEdge> edges;

    public static class BuildNode {
        String id;                         // unique node ID
        String type;                       // "module", "phase", "artifact"
        BuildAction action;                // what to execute
        long estimatedMemoryMb;
        Set<String> tags;                  // "debug", "release", "compose", etc.
    }

    public static class BuildEdge {
        String from;
        String to;
        EdgeType type;                     // PRODUCES, CONSUMES, DEPENDS_ON
    }

    public enum EdgeType { PRODUCES, CONSUMES, DEPENDS_ON }
}
```

### 5.4 ArtifactKey (Cache Key)

```java
public class ArtifactKey {
    String phase;                         // phase that produced the artifact
    String projectId;                     // hash of project path
    String inputHash;                     // SHA-256 of all input file contents + config
    String toolVersion;                   // version of tool that created it
    String variant;                       // "debug" / "release"

    String toCachePath();                 // derived file path in cache store
    String toJson();                      // serialization
}
```

### 5.5 ResourceBundle (AAPT2 output)

```java
public class ResourceBundle {
    Path resourcesArsc;                   // compiled resource table
    List<Path> compiledResDirectories;    // res/ contents
    Path rJava;                           // R.java source file
    Path manifest;                        // merged binary AndroidManifest.xml
    Set<String> configurations;           // e.g., ["en", "fr", "hdpi", "xhdpi"]
    Map<String, Integer> resourceIds;     // R.id.myView → 0x7F010002
}
```

### 5.6 DexOutput

```java
public class DexOutput {
    List<Path> dexFiles;                  // classes.dex, classes2.dex, ...
    Path mappingFile;                     // proguard mapping (release only)
    int totalMethodCount;
    int totalFieldCount;
    int dexFileCount;
    boolean isOptimized;                  // true if R8 was applied
}
```

### 5.7 SignedApk

```java
public class SignedApk {
    Path apkPath;
    SignatureInfo signatureInfo;
    long sizeBytes;
    boolean aligned;
    boolean v1Signed;
    boolean v2Signed;
    boolean v3Signed;
}
```

### 5.8 BuildContext

```java
public class BuildContext {
    String buildId;                        // UUID
    BuildRequest request;
    BuildGraph graph;
    PhaseState state;                      // mutable phase outputs
    Config config;                         // validated engine configuration
    Instant startTime;
    CancellationToken cancellationToken;
    Map<String, Object> metadata;          // custom key/value
}
```

### 5.9 Config

```java
public class Config {
    // Paths
    Path hbeHome;                          // ~/.hbe
    Path sdkHome;                          // ~/.hbe/sdk
    Path cacheHome;                        // ~/.hbe/cache
    Path buildOutputDir;                   // ~/.hbe/build

    // Cache
    long cacheMaxBytes;                    // 2GB default
    long cacheMinFreeStorageBytes;         // stop caching when free < 500MB

    // Memory
    int defaultRamBudgetMb;                // 1024
    int batchSizeJava;                     // 50 files
    int batchSizeKotlin;                   // 30 files
    int dexChunkSize;                      // 200 class files

    // Network
    int connectTimeoutMs;                  // 10000
    int readTimeoutMs;                     // 30000
    int maxRetries;                        // 3

    // Behavior
    boolean autoDownloadSdk;               // true
    boolean autoCreateDebugKeystore;        // true
    boolean parallelPhases;                // false (sequential by default)
    boolean daemonEnabled;                 // false
    String logLevel;                       // "INFO"
    LogOutput logOutput;                   // stdout
}
```

---

## 6. Build Graph Model

### 6.1 Purpose

The build graph is a DAG (Directed Acyclic Graph) where nodes represent either phases, modules, or artifacts. Edges represent dependencies, production, or consumption relationships. The task scheduler traverses this graph to determine execution order, parallelism, and caching eligibility.

### 6.2 Graph Construction

```
Project detection
    │
    ▼
Module discovery ──────► Each source directory / build config = one module
    │
    ▼
Phase generation ──────► Each module gets standard phases (compile, dex, etc.)
    │
    ▼
Dependency wiring ──────► Edges between modules and phases based on dependency graph
    │
    ▼
Graph validation ──────► Cycle detection, missing artifact detection
    │
    ▼
Topological sort ──────► Execution plan (list of phases in order)
```

### 6.3 Node Types

| Node Type | Description | Example |
|-----------|-------------|---------|
| `SDK_RESOLVE` | Resolve SDK components | One per unique compileSdk |
| `DEP_RESOLVE` | Resolve dependencies | One per module |
| `MANIFEST_MERGE` | Merge manifests | One per module |
| `RES_COMPILE` | Compile resources | One per module |
| `RES_LINK` | Link compiled resources | One per module |
| `SOURCE_COMPILE` | Compile Java/Kotlin | One per module (subdivided into batches) |
| `DEX` | Convert classes to dex | One per module |
| `R8` | Optimize dex | One per variant (release only) |
| `PACKAGE` | Assemble APK | One per variant |
| `SIGN` | Sign APK | One per variant |
| `ALIGN` | Zipalign APK | One per variant |

### 6.4 Edge Types

| Edge Type | Meaning | Scheduling Implication |
|-----------|---------|----------------------|
| `DEPENDS_ON` | A must complete before B can start | Sequential |
| `PRODUCES` | A produces artifact X that B consumes | Sequential |
| `CONSUMES` | A consumes artifact Y from B | Sequential |
| `SAME_RESOURCE` | A and B share a locked resource | Sequential over shared resource |
| `INDEPENDENT` | No edge; can run in parallel | Scheduled in parallel |

### 6.5 Graph Validation

```java
void validate(BuildGraph graph) throws InvalidGraphException {
    // 1. Cycle detection (DFS)
    // 2. Orphan detection (node with no path to final PACKAGE node)
    // 3. Missing producer detection (edge references nonexistent node)
    // 4. Type consistency (edge types match node types)
    // 5. RAM feasibility check (sum of parallel nodes <= budget)
}
```

### 6.6 Multi-Module Graph

For multi-module projects, each module generates its own subgraph. Subgraphs are merged:

```
:app module graph         :library module graph
    │                            │
    ▼                            ▼
SDK_RESOLVE ──(shared)── SDK_RESOLVE
DEP_RESOLVE ──(shared)── DEP_RESOLVE
MANIFEST_MERGE           MANIFEST_MERGE
RES_COMPILE              RES_COMPILE
RES_LINK                 RES_LINK
SOURCE_COMPILE ──PRODUCES──► AAR_PACKAGE
                              │
DEX ◄── CONSUMES ── AAR
PACKAGE
SIGN
```

The library's AAR becomes an input to the app's SOURCE_COMPILE phase.

---

## 7. Task Scheduler Design

### 7.1 Purpose

The TaskScheduler takes a validated BuildGraph and produces an execution plan: an ordered list of phases with parallelism hints. It respects RAM budgets, module boundaries, and caching opportunities.

### 7.2 Scheduling Algorithm

```
Input: BuildGraph graph, Config config
Output: ExecutionPlan plan

Algorithm:

1. Topologically sort all nodes (Kahn's algorithm)
2. For each node, compute:
   - estimatedMemory = Node.estimatedMemoryMb
   - isCacheable = node.output is deterministic by input hash
   - cacheKey = ArtifactKey(node, current inputs)
3. Check cache for each cacheable node
   - If cache hit → node.markCached(); skip execution
4. Group nodes into batches:
   - Start with sorted list
   - Iterate through list; for each node:
     - If no remaining dependency in batch → add to current batch
     - Else → start new batch
5. Check RAM feasibility for each batch:
   - sum(estimatedMemory for all nodes in batch) <= ramBudget
   - If exceeds budget → split batch further
6. Return ExecutionPlan(orderedBatches)
```

### 7.3 Execution Plan

```java
public class ExecutionPlan {
    List<ExecutionBatch> batches;        // sequential batches
}

public class ExecutionBatch {
    List<BuildNode> nodes;               // run in parallel within batch
    long totalEstimatedMemoryMb;
    boolean isParallel;                  // true if >1 node in batch
}
```

### 7.4 Execution Loop

```java
for (ExecutionBatch batch : executionPlan.batches) {
    if (cancellationToken.isCancelled()) break;
    
    if (batch.isParallel && memoryManager.hasBudgetFor(batch)) {
        // Execute batch nodes in parallel
        List<CompletableFuture<PhaseResult>> futures = batch.nodes
            .map(node -> asyncExecute(node))
            .collect(toList());
        for (var future : futures) {
            var result = future.get();  // blocking, collects results
            handleResult(result);
        }
    } else {
        // Execute batch nodes sequentially
        for (BuildNode node : batch.nodes) {
            var result = execute(node);
            handleResult(result);
        }
    }
    
    // Phase transition: release memory
    memoryManager.releaseMemory();
    diagnostics.recordPhaseTiming(...);
}
```

### 7.5 Cancellation

- `CancellationToken` is checked between phases and between batches
- Long-running external processes (javac, d8) are killed via `Process.destroyForcibly()`
- Cancellation propagates: killing one node in a batch cancels the entire batch
- Partially completed work is discarded (not checkpointed)

### 7.6 Scheduling for Low RAM

- When `ramBudgetMb < 2048`: all batches are single-node (fully sequential)
- When `ramBudgetMb >= 2048`: batches may contain parallel nodes if RAM allows
- No two JVM-heavy phases (kotlinc + d8) ever execute in parallel
- Light phases (SDK resolve, dependency resolve) may run together

---

## 8. Dependency Resolution Algorithm

### 8.1 Purpose

Resolve Maven coordinates to artifacts (AAR/JAR) by traversing POM dependency trees. Download and cache artifacts locally. Extract AAR contents for compilation.

### 8.2 Algorithm: Resolve

```
Input: Set<MavenCoordinate> roots, List<Repository> repositories
Output: DependencyGraph

Algorithm:

Function resolve(roots, repos):
    graph = new DependencyGraph()
    visited = new HashSet<MavenCoordinate>()
    queue = new ArrayDeque<MavenCoordinate>(roots)
    
    while queue is not empty:
        coord = queue.poll()
        if visited.contains(coord): continue
        visited.add(coord)
        
        // Fetch POM
        pom = fetchPom(coord, repos)
        if pom == null: throw ResolutionException(coord)
        
        node = DependencyNode(coord)
        node.scope = pom.scope
        node.optional = pom.optional
        node.excludes = pom.excludes
        
        for dep in pom.dependencies:
            if dep.optional: continue
            if dep.scope in ["test", "provided"] and isMainBuild: continue
            if excludedBy(node.excludes, dep): continue
            
            // Version conflict resolution: nearest-wins
            resolvedDep = resolveConflict(dep, visited)
            edge = DependencyEdge(resolvedDep, dep.scope)
            node.dependencies.add(edge)
            queue.add(resolvedDep)
        
        graph.roots.add(node)
    
    return graph

Function resolveConflict(dep, visited):
    for existing in visited:
        if existing.groupId == dep.groupId 
           and existing.artifactId == dep.artifactId:
            // Nearest-wins: first encountered version wins
            return existing
    return dep
```

### 8.3 Algorithm: Fetch POM

```
Input: MavenCoordinate coord, List<Repository> repos
Output: Pom (parsed)

Algorithm:

Function fetchPom(coord, repos):
    pomPath = coord.toPath().replace(".aar", ".pom").replace(".jar", ".pom")
    
    for repo in repos:
        url = repo.url + "/" + pomPath
        cacheKey = "pom:" + coord.toNotation()
        
        // Check local cache first
        cached = cacheManager.get(cacheKey)
        if cached != null: return parsePom(cached)
        
        // Download
        try:
            content = httpClient.get(url)
            cacheManager.put(cacheKey, content)
            return parsePom(content)
        catch (HttpException e):
            if e.statusCode == 404: continue
            throw e
    
    return null  // not found in any repo
```

### 8.4 Algorithm: AAR Extraction

```
Input: MavenCoordinate coord, Path localAarFile
Output: AarContents (extracted paths)

Algorithm:

Function extractAar(coord, localAarFile):
    extractDir = cacheHome + "/aar/" + coord.toNotation() + "/" + coord.version
    lockFile = extractDir + ".lock"
    
    // Check extraction cache
    if exists(extractDir + "/.extracted"):
        return AarContents(extractDir)
    
    // Lock to prevent concurrent extraction
    try (lock = FileLock(lockFile)) {
        // Double-check after acquiring lock
        if exists(extractDir + "/.extracted"):
            return AarContents(extractDir)
        
        mkdirs(extractDir)
        unzip(localAarFile, extractDir)
        
        // Verify essential files
        assert exists(extractDir + "/classes.jar")
        
        // Mark as extracted
        write(extractDir + "/.extracted", coord.version)
        
        return AarContents(extractDir)
    }

class AarContents:
    Path extractDir;
    Path classesJar;       // extractDir + "/classes.jar"
    Path manifest;          // extractDir + "/AndroidManifest.xml"
    Path rTxt;              // extractDir + "/R.txt"
    Path resDir;            // extractDir + "/res/"
    Path assetsDir;         // extractDir + "/assets/"
    Path libsDir;           // extractDir + "/lib/"
    Path proguardTxt;       // extractDir + "/proguard.txt"
    Path lintJar;           // extractDir + "/lint.jar" (optional)
```

### 8.5 Edge Cases

| Case | Handling |
|------|----------|
| Circular dependency | Detect via visited set; break cycle with error |
| Version range | Parse Maven range syntax; resolve to highest in range |
| Snapshot version | Cache for 24h; re-download if expired |
| Missing POM | Fallback to jar-only artifact (assume no transitive deps) |
| Platform dependency (e.g., `androidx.core:core`) | Include in graph but mark as `provided` scope |
| Dependency with `@aar` classifier | Override extension to AAR |
| HTTP 429 (rate limit) | Retry with exponential backoff (1s, 2s, 4s; max 30s) |
| Repository authentication | Basic auth via repository URL credentials |
| Missing SHA-1/MD5 checksum | Warn but proceed (configurable: strict mode fails) |

### 8.6 Performance

- POM parsing cost: ~5ms per POM (SAX parser, not DOM)
- Typical app resolves 50-200 POMs → 250-1000ms
- AAR extraction: ~100ms per AAR (unzip + verify)
- Network latency: depends on repo; first build may take 30-60s
- All resolved artifacts cached with SHA-256 keys


---

## 9. SDK Management Algorithm

### 9.1 Purpose

Auto-detect, download, install, and manage Android SDK components (platforms, build-tools, cmdline-tools, NDK) and JDK. The engine should never require manual SDK setup.

### 9.2 SDK Directory Structure

```
~/.hbe/sdk/
├── platforms/
│   ├── android-34/
│   │   ├── android.jar
│   │   ├── data/
│   │   └── ...
│   └── android-35/
├── build-tools/
│   └── 34.0.0/
│       ├── aapt2
│       ├── d8
│       ├── apksigner
│       └── zipalign
├── cmdline-tools/
│   └── latest/
│       └── bin/
│           └── sdkmanager
├── ndk/
│   └── 26.1.10909125/
│       ├── toolchains/
│       └── ...
├── platform-tools/
│   ├── adb
│   └── ...
├── jdk/
│   └── 17.0.9/
└── .hbe-sdk.json                  # manifest of installed components
```

### 9.3 Algorithm: Resolve SDK

```
Input: int compileSdk, String buildToolsVersion, boolean needNdk
Output: SdkResolution (paths to tools)

Algorithm:

Function resolveSdk(compileSdk, buildToolsVersion, needNdk):
    resolution = new SdkResolution()
    
    // Step 1: Locate existing SDK installations
    resolution.sdkRoot = findExistingSdkRoot()
    // search order: request.sdkHome → ANDROID_HOME → ANDROID_SDK_ROOT
    //              → ~/.hbe/sdk → ~/Android/Sdk
    
    // Step 2: Resolve JDK
    resolution.jdkHome = findOrDownloadJdk()
    
    // Step 3: Resolve build-tools
    btVersion = buildToolsVersion ?: findLatestBuildTools(resolution.sdkRoot)
    resolution.buildToolsDir = resolveBuildTools(resolution.sdkRoot, btVersion)
    
    // Step 4: Resolve platform
    resolution.platformDir = resolvePlatform(resolution.sdkRoot, compileSdk)
    
    // Step 5: Resolve cmdline-tools
    resolution.cmdlineToolsDir = resolveCmdlineTools(resolution.sdkRoot)
    
    // Step 6: Resolve platform-tools (for adb)
    resolution.platformToolsDir = resolvePlatformTools(resolution.sdkRoot)
    
    // Step 7: Resolve NDK (optional)
    if needNdk:
        resolution.ndkDir = resolveNdk(resolution.sdkRoot)
    
    // Step 8: Verify critical tool binaries
    for tool in [aapt2, d8, apksigner, zipalign, adb]:
        assert exists(resolution.getToolPath(tool))
    
    return resolution
```

### 9.4 Algorithm: Download Platform

```
Input: int apiLevel, Path sdkRoot
Output: Path platformDir

Algorithm:

Function downloadPlatform(apiLevel, sdkRoot):
    if exists(sdkRoot + "/platforms/android-" + apiLevel):
        return sdkRoot + "/platforms/android-" + apiLevel
    
    // Try via sdkmanager first
    try:
        sdkmanager = findSdkmanager(sdkRoot)
        run(sdkmanager, "--install", "platforms;android-" + apiLevel)
        return sdkRoot + "/platforms/android-" + apiLevel
    catch (SdkmanagerNotFoundException):
        // Fallback: direct download from Google's repository
        url = "https://dl.google.com/android/repository/"
            + "platform-${apiLevel}_rXX.zip"
        // Version number lookup via XML repository
        xml = httpClient.get("https://dl.google.com/android/repository/repository2-1.xml")
        version = parsePlatformVersion(xml, apiLevel)
        downloadUrl = "https://dl.google.com/android/repository/"
            + "platform-${apiLevel}_r${version}.zip"
        downloadAndExtract(downloadUrl, sdkRoot + "/platforms/")
        return sdkRoot + "/platforms/android-" + apiLevel
```

### 9.5 Algorithm: Download or Find JDK

```
Input: none (uses config)
Output: Path jdkHome

Algorithm:

Function findOrDownloadJdk():
    // 1. Check JAVA_HOME
    if JAVA_HOME is set and has java binary:
        return JAVA_HOME
    
    // 2. Check bundled JDK (for Android app embedding)
    if exists(hbeHome + "/jdk"):
        return hbeHome + "/jdk"
    
    // 3. Check system Java
    java = which("java")
    if java != null:
        jdkHome = resolveJdkHome(java)
        version = getJavaVersion(jdkHome)
        if version.major >= 17:
            return jdkHome
    
    // 4. Download JDK (Adoptium/Temurin)
    url = "https://api.adoptium.net/v3/binary/latest/17/ga/linux/arm64/jdk/hotspot/normal/eclipse"
    downloadAndExtract(url, hbeHome + "/jdk/")
    return hbeHome + "/jdk/" + getSingleSubdirectory(hbeHome + "/jdk/")
```

### 9.6 License Acceptance

```
Function acceptLicenses(sdkRoot):
    licenseDir = sdkRoot + "/licenses/"
    mkdirs(licenseDir)
    
    // Write accepted licenses (mimics sdkmanager --licenses)
    // android-sdk-license: hash of accepted text
    write(licenseDir + "/android-sdk-license", "8933bad161af4178b1185d1a37fbf41ea5269c55")
    write(licenseDir + "/android-sdk-preview-license", "84831b9409646a918e30573bab4c9c91346d8abd")
    write(licenseDir + "/google-gdk-license", "33b6a2b64607f11b759f320ef9dff4ae5c47d97a")
```

### 9.7 Resolution Failures

| Failure | Handling |
|---------|----------|
| No network | Use cached SDK; fail only if SDK not cached |
| SDK not found in cache | Build fails with `SDK_NOT_FOUND` + download instructions |
| Corrupted SDK binary | Verify SHA-256 before use; delete and re-download on mismatch |
| JDK not found | Auto-download JDK 17 (LTS) as fallback |
| Platform zip corrupt | Redownload with `?retry=1`; fail on second corruption |

---

## 10. Resource Compilation Workflow

### 10.1 Purpose

Compile Android XML resources to binary format, generate the resource table (`resources.arsc`), produce `R.java` for source compilation, and merge AndroidManifest.xml files.

### 10.2 AAPT2 Integration

HBE uses AAPT2 (Android Asset Packaging Tool 2) from the Android SDK build-tools. AAPT2 operates in two phases:

1. **Compile**: Each resource file → `.flat` intermediate file
2. **Link**: All `.flat` files → `resources.arsc` + `R.java`

### 10.3 Workflow: Resource Compile

```
Input: Path resDir, Path outputDir
Output: List<Path> flatFiles

Algorithm:

Function compileResources(resDir, outputDir):
    flatFiles = []
    
    for each file in walkFiles(resDir):
        if isResourceFile(file):
            outputFlat = outputDir + "/" + relativePath(file) + ".flat"
            mkdirs(parent(outputFlat))
            
            // Determine AAPT2 arguments based on file type
            args = ["aapt2", "compile"]
            args.add("-o", outputDir)
            args.add("--dir", resDir)  // batch compile whole dir
            
            // Alternative: per-file compile for incremental
            // args.add(filePath)
            
            processRunner.run("aapt2", args)
            flatFiles.add(outputFlat)
    
    return flatFiles
```

Optimization: Use `aapt2 compile --dir res/` for initial build (single command). Use per-file compile for incremental builds.

### 10.4 Workflow: Resource Link

```
Input: List<Path> flatFiles, Path manifest, Path outputDir
       int compileSdk, List<Path> extraPackages
Output: ResourceBundle

Algorithm:

Function linkResources(flatFiles, manifest, outputDir, compileSdk, extraPackages):
    args = ["aapt2", "link"]
    args.add("-o", outputDir + "/resources.apk")  // intermediate package
    args.add("--manifest", manifest)
    args.add("-I", sdkPath + "/platforms/android-" + compileSdk + "/android.jar")
    args.add("--java", outputDir + "/gen")  // R.java output dir
    args.add("--auto-add-overlay")
    args.add("--output-text-symbols", outputDir)  // R.txt
    
    // Add all flat files
    for flat in flatFiles:
        args.add("-R", flat)
    
    // Add static libraries / dependency packages
    for pkg in extraPackages:
        args.add("--extra-packages", pkg)
    
    processRunner.run("aapt2", args)
    
    return ResourceBundle(
        resourcesArsc = outputDir + "/resources.apk",
        rJava = outputDir + "/gen/" + packagePath(manifest) + "/R.java",
        compiledResDirectories = [outputDir + "/res/"]
    )
```

### 10.5 Manifest Merging

```
Input: List<ManifestSource> manifests (main + dependencies + build type)
Output: Path mergedManifest

Algorithm:

Function mergeManifests(manifests, outputDir):
    if manifests.size() == 1:
        return manifests[0].path  // no merge needed
    
    // Use AAPT2 for merging
    args = ["aapt2", "link"]
    args.add("--manifest", manifests[0].path)  // main manifest
    for dep in manifests[1:]:
        args.add("--merge", dep.path)
    
    // OR use simple XML DOM merging for core logic
    // (AAPT2 link --merge is preferred)
    
    result = processRunner.run("aapt2", args)
    return outputDir + "/AndroidManifest.xml"
```

### 10.6 Incremental Resource Compilation

Detect changed resource files by comparing SHA-256 hashes:

```
Function getChangedResources(resDir, previousHashes):
    currentHashes = computeHashes(resDir)
    changed = []
    for file, hash in currentHashes:
        if previousHashes[file] != hash:
            changed.add(file)
    
    for file in previousHashes:
        if not exists(file):
            changed.add(file)  // deleted file → re-link
    
    return changed
```

- Changed/deleted files → recompile only those files → re-link all
- Unchanged files → reuse cached `.flat` files
- Full re-link still required because resource IDs may shift

### 10.7 Edge Cases

| Case | Handling |
|------|----------|
| Empty res directory | Warn but continue; no resource compilation |
| Invalid XML resource | aapt2 reports error; HBE parses and surfaces line number |
| Resource ID conflict (overlay) | `--auto-add-overlay` + ordered overlay processing |
| AAPT2 binary not found | Download build-tools for target API level |
| Large resource count (>10k) | Compile in batches to limit argument length |
| Generated resources (data binding) | Must be generated before source compilation phase |
| Non-standard resource types | aapt2 handles all standard types automatically |

---

## 11. Java Compilation Workflow

### 11.1 Purpose

Compile Java source files to `.class` files using the JDK Compiler API (preferred) or `javac` process (fallback). Include Android SDK classes, dependency jars, and generated R.java on the classpath.

### 11.2 Strategy: JDK Compiler API vs javac Process

| Approach | Pros | Cons |
|----------|------|------|
| JDK Compiler API (`javax.tools.JavaCompiler`) | No process spawn; in-memory compilation; streaming error handling | Java-only; cannot compile Kotlin |
| `javac` process | Works with any JDK; familiar tooling; supports all flags | 1-3s JVM startup per batch; process management |

**Decision**: Use JDK Compiler API for Java batches (faster, lower RAM), fall back to `javac` process when Compiler API is unavailable or when custom flags are needed.

### 11.3 Workflow: Java Compilation

```
Input: Set<Path> javaSourceFiles, Classpath classpath, Path outputDir
Output: Set<Path> classFiles

Algorithm:

Function compileJava(sources, classpath, outputDir):
    // Step 1: Validate sources
    if sources.isEmpty():
        return emptySet()
    
    // Step 2: Create output directory
    mkdirs(outputDir)
    
    // Step 3: Compile via JDK Compiler API
    compiler = ToolProvider.getSystemJavaCompiler()
    if compiler != null:
        return compileWithJdkApi(compiler, sources, classpath, outputDir)
    else:
        return compileWithJavacProcess(sources, classpath, outputDir)
    
    // Step 4: Collect output .class files
    classFiles = walkFiles(outputDir, "*.class")
    return classFiles

Function compileWithJdkApi(compiler, sources, classpath, outputDir):
    fileManager = compiler.getStandardFileManager(null, null, null)
    
    // Configure compilation units
    compilationUnits = sources.map { 
        fileManager.getJavaFileObjectFromPath(it) 
    }
    
    // Configure options
    options = [
        "-d", outputDir,
        "-classpath", classpath.toString(),  // all dependency jars
        "-source", "17",
        "-target", "17",
        "-Xlint:-options"
    ]
    
    // Add annotation processor paths
    if classpath.hasAnnotationProcessors():
        options.add("-processorpath", classpath.getProcessorPath())
    
    // Compile
    task = compiler.getTask(null, fileManager, diagnosticListener, options, null, compilationUnits)
    success = task.call()
    
    if !success:
        throw CompilationException("Java compilation failed", diagnosticListener.getErrors())

Function compileWithJavacProcess(sources, classpath, outputDir):
    args = [findJavac()]
    args.add("-d", outputDir)
    args.add("-cp", classpath.toString())
    args.add("-source", "17")
    args.add("-target", "17")
    
    for source in sources:
        args.add(source)
    
    result = processRunner.run("javac", args, timeout = 120_000)
    
    if result.exitCode != 0:
        throw CompilationException("Java compilation failed", result.stderr)
```

### 11.4 Classpath Construction

```
Function buildClasspath(sdkResolution, dependencyGraph, rJavaDir, aarContents):
    cp = new Classpath()
    
    // Android SDK
    cp.add(sdkResolution.androidJar)  // ~/android.jar
    
    // Generated R.java
    cp.add(rJavaDir)
    
    // Dependency jars (from extracted AARs)
    for aar in aarContents:
        cp.add(aar.classesJar)
    
    // Direct jar dependencies
    for jar in resolvedJars:
        cp.add(jar)
    
    // Annotation processors
    for processor in findAnnotationProcessors(aarContents):
        cp.addProcessor(processor)
    
    return cp
```

### 11.5 Batch Compilation (Low RAM)

```
Function batchCompileJava(allSources, classpath, outputDir, batchSize):
    classFiles = []
    batches = partition(allSources, batchSize)  // e.g., 50 files/batch
    
    for batch in batches:
        batchOutput = outputDir + "/batch-" + batchIndex
        batchClassFiles = compileJava(batch, classpath, batchOutput)
        classFiles.addAll(batchClassFiles)
        
        // Release memory
        System.gc()
        memoryManager.releaseMemory()
    
    return classFiles
```

### 11.6 Edge Cases

| Case | Handling |
|------|----------|
| No Java sources | Skip phase; return empty set |
| Compilation error | Collect all errors; return with line numbers + file paths |
| Annotation processing | Add processor path; run with `-processor` flag |
| Java version mismatch | Warn if source version > JDK version; fail for incompatible features |
| Encoding issues | Assume UTF-8; fail on non-UTF-8 with error message |
| Very large source files (>1MB) | Compile individually to isolate OOM risk |

---

## 12. Kotlin Compilation Workflow

### 12.1 Purpose

Compile Kotlin source files to `.class` files using the `kotlinc` compiler. Kotlin compilation is the heaviest phase and requires careful batching for low-RAM targets.

### 12.2 Strategy

- Always use `kotlinc` process (no in-process API available)
- Batch files into groups of 30 (configurable)
- Set `-J-Xmx256m` to cap per-process heap
- Kotlin compiler daemon disabled by default (enabled only on 8GB+ devices)

### 12.3 Workflow: Kotlin Compilation

```
Input: Set<Path> kotlinSourceFiles, Classpath classpath, 
       Path outputDir, boolean useCompose
Output: Set<Path> classFiles

Algorithm:

Function compileKotlin(sources, classpath, outputDir, useCompose):
    if sources.isEmpty():
        return emptySet()
    
    mkdirs(outputDir)
    
    args = [findKotlinc()]
    args.add("-d", outputDir)
    args.add("-classpath", classpath.toString())
    args.add("-jvm-target", "17")
    args.add("-Xjvm-default=all")              // generate default methods
    
    // Kotlin language settings
    args.add("-Xlambdas=indy")                  // use invokedynamic
    args.add("-Xcontext-receivers")             // enable context receivers
    
    // Compose compiler plugin
    if useCompose:
        composePluginPath = findComposeCompilerPlugin()
        args.add("-Xplugin=" + composePluginPath)
        args.add("-P", "plugin:androidx.compose.compiler.plugins.kotlin:" 
            + "suppressKotlinVersionCompatibilityCheck=true")
        args.add("-P", "plugin:androidx.compose.compiler.plugins.kotlin:"
            + "generateFunctionParameters=true")
    
    // Annotation processing via KSP or kapt
    if classpath.hasKspProcessors():
        args.add("-Xplugin=" + findKspPlugin())
        args.add("-P", "plugin:com.google.devtools.ksp.symbol-processing:"
            + "kspOutputDir=" + outputDir + "/ksp")
    elif classpath.hasKaptProcessors():
        args.add("-Xplugin=" + findKaptPlugin())
    
    // Sources
    for source in sources:
        args.add(source)
    
    // Memory limit
    args.add("-J-Xmx256m")
    
    result = processRunner.run("kotlinc", args, timeout = 180_000)
    
    if result.exitCode != 0:
        throw CompilationException("Kotlin compilation failed", 
            parseKotlinErrors(result.stderr))
    
    return walkFiles(outputDir, "*.class")
```

### 12.4 Kotlin Error Parsing

Kotlin compiler errors follow a consistent format:

```
e: /path/to/File.kt: (line, col): Error message
e: /path/to/File.kt: (line, col): Warning message
```

Parser:

```
Function parseKotlinErrors(stderr):
    errors = []
    for line in stderr.lines():
        if line.startsWith("e:"):
            match = REGEX_KOTLIN_ERROR.match(line)
            if match:
                errors.add(CompilerError(
                    file = match.group("file"),
                    line = parseInt(match.group("line")),
                    column = parseInt(match.group("col")),
                    message = match.group("message")
                ))
    return errors
```

### 12.5 Batch Compilation Strategy

```
Function batchCompileKotlin(allSources, classpath, outputDir, useCompose, batchSize):
    // Kotlin files often depend on each other
    // Simple partitioning by file (not dependency-aware)
    
    // Phase 1: Analyze source dependencies (lightweight)
    dependencyMap = analyzeKotlinDependencies(allSources)
    // Produces: Map<Path, Set<Path>> — file → files it imports
    
    // Phase 2: Topological sort by dependency
    sortedSources = topologicalSort(allSources, dependencyMap)
    
    // Phase 3: Batch in order
    classFiles = []
    batches = partition(sortedSources, batchSize)
    
    // Ensure each batch can compile independently
    // If batch A depends on classes from batch B (which hasn't compiled yet),
    // the compilation will fail. Solution: compile in dependency order.
    
    for batch in batches:
        // Add previously compiled class files to classpath
        batchClasspath = classpath.clone()
        batchClasspath.addOutputDir(outputDir)  // previous batches' output
        
        batchOutput = outputDir
        batchClassFiles = compileKotlin(batch, batchClasspath, batchOutput, useCompose)
        classFiles.addAll(batchClassFiles)
        
        memoryManager.releaseMemory()
    
    return classFiles
```

### 12.6 Compose Compiler Plugin Detection

```
Function findComposeCompilerPlugin():
    // Search order:
    // 1. Bundled with HBE
    // 2. ~/.hbe/plugins/compose-compiler-<kotlin-version>.jar
    // 3. Downloaded from Maven Central
    //    org.jetbrains.compose.compiler:compiler:<version>
    
    kotlinVersion = getKotlinVersion()
    bundlePath = hbeHome + "/plugins/compose-compiler-" + kotlinVersion + ".jar"
    
    if exists(bundlePath):
        return bundlePath
    
    // Download from Maven
    coord = MavenCoordinate("org.jetbrains.compose.compiler", 
        "compiler", composeCompilerVersionFor(kotlinVersion))
    return dependencyManager.resolveAndDownload(coord)
```

### 12.7 Edge Cases

| Case | Handling |
|------|----------|
| No Kotlin sources | Skip phase (only compile Java) |
| Mixed Java/Kotlin | Compile Kotlin first, then Java with Kotlin classes on classpath |
| Kotlin version mismatch | Kotlin compiler version must match Kotlin stdlib version |
| Compose plugin version mismatch | Match compose compiler version to Kotlin version; fail if incompatible |
| KSP processor crash | Capture error output; fail build with processor error details |
| Out of memory in kotlinc | Kill process; reduce batch size; retry once |


---

## 13. Compose Compilation Workflow

### 13.1 Purpose

The Compose compiler plugin transforms `@Composable` Kotlin functions during compilation, generating the runtime code that enables declarative UI recomposition. This is a Kotlin compiler plugin, not a separate tool.

### 13.2 Integration

Compose compilation is not a separate phase — it is a flag passed to `kotlinc` (see §12.3). The `-Xplugin` flag points to the Compose compiler JAR, which hooks into the Kotlin compiler's IR (Intermediate Representation) pipeline.

### 13.3 Compose Compiler Plugin Selection

The plugin version must match the Kotlin compiler version exactly. Mapping:

| Kotlin Version | Compose Compiler Version |
|---------------|--------------------------|
| 1.9.0 | 1.5.1 |
| 1.9.10 | 1.5.3 |
| 1.9.20 | 1.5.4 |
| 1.9.21 | 1.5.7 |
| 1.9.22 | 1.5.8 |
| 2.0.0 | 1.6.0-rc01 |
| 2.0.21 | 1.6.10 |
| 2.1.0 | 1.7.0 |

If no mapping exists for the current Kotlin version, the build fails with a clear message suggesting compatible versions.

### 13.4 Plugin Configuration

```
-Xplugin=/path/to/compose-compiler.jar
-P plugin:androidx.compose.compiler.plugins.kotlin:suppressKotlinVersionCompatibilityCheck=true
-P plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionParameters=true
-P plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=/path/to/metrics
-P plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=/path/to/reports
-P plugin:androidx.compose.compiler.plugins.kotlin:liveLiteralsEnabled=false  // debug only
```

### 13.5 Live Literals (Debug Only)

- Enabled only for debug builds
- Allows modifying Compose literals at runtime via Android Studio or custom tooling
- Adds overhead to `.class` file size (~10%)
- Disabled for release builds

### 13.6 Compose Metrics

When `metricsDestination` is set, the Compose compiler generates:
- **Composables metrics** — count of composable functions, restart groups
- **Class metrics** — composition class sizes
- **Report** — HTML/human-readable report

HBE collects these when available and includes them in `DiagnosticReport`.

---

## 14. DEX Generation Workflow

### 14.1 Purpose

Convert Java/Kotlin `.class` files to Dalvik Executable (`.dex`) format using `d8`. Handle multi-dex (64k method limit). Optionally apply R8 optimization for release builds.

### 14.2 d8 Integration

**Tool**: `d8` from Android SDK build-tools (included with build-tools 28.0.0+)

### 14.3 Workflow: DEX Generation

```
Input: Set<Path> classFiles, DexConfig config, Path outputDir
Output: DexOutput

Algorithm:

Function generateDex(classFiles, config, outputDir):
    if classFiles.isEmpty():
        throw DexException("No class files to dex")
    
    // Step 1: Count methods to determine if multi-dex needed
    methodCount = countMethods(classFiles)
    needsMultiDex = methodCount > 64000 || classFiles.size() > 10000
    
    // Step 2: Build main dex list (classes needed for cold start)
    mainDexList = null
    if needsMultiDex:
        mainDexList = buildMainDexList(classFiles, config)
    
    // Step 3: Invoke d8
    args = ["d8"]
    args.add("--release")                    // release mode (smaller dex)
    if config.debug:
        args.add("--debug")                  // debug mode (faster build)
    
    args.add("--min-api", config.minSdk.toString())
    args.add("--output", outputDir)
    
    // Multi-dex
    if needsMultiDex:
        args.add("--multi-dex")
        if mainDexList != null:
            args.add("--main-dex-list", mainDexList)
            args.add("--minimal-main-dex")
    
    // Class files
    args.addAll(classFiles.map { it.toString() })
    
    result = processRunner.run("d8", args, timeout = 300_000)
    
    if result.exitCode != 0:
        throw DexException("d8 failed: " + result.stderr)
    
    // Step 4: Collect output
    dexFiles = sorted(listFiles(outputDir, "*.dex"))
    
    return DexOutput(
        dexFiles = dexFiles,
        totalMethodCount = methodCount,
        dexFileCount = dexFiles.size(),
        isOptimized = false
    )
```

### 14.4 Multi-Dex Strategy

```
Function buildMainDexList(classFiles, config):
    // Main dex must contain:
    // 1. Application class
    // 2. Activity, Service, BroadcastReceiver, ContentProvider classes
    // 3. Classes referenced in AndroidManifest.xml
    // 4. Annotation classes used in manifest
    
    mainDex = new HashSet<Path>()
    
    // Parse manifest for component classes
    manifestClasses = parseManifestClasses(config.manifest)
    for cls in classFiles:
        className = pathToClassName(cls)
        if className in manifestClasses:
            mainDex.add(cls)
    
    // Add Application class and its dependencies
    applicationClass = findApplicationClass(classFiles, config.manifest)
    if applicationClass != null:
        mainDex.add(applicationClass)
        mainDex.addAll(findDirectDependencies(applicationClass, classFiles))
    
    // Write main-dex-list file
    listFile = outputDir + "/main-dex-list.txt"
    write(listFile, mainDex.map { pathToClassName(it) })
    
    return listFile
```

### 14.5 Chunked Dexing (Low RAM)

For low-RAM targets, dex class files in chunks:

```
Function chunkedDex(classFiles, config, outputDir, chunkSize):
    allDexFiles = []
    chunks = partition(classFiles, chunkSize)  // e.g., 200 files/chunk
    
    for (i, chunk) in chunks:
        chunkOutput = outputDir + "/chunk-" + i
        mkdirs(chunkOutput)
        
        result = generateDex(chunk, config, chunkOutput)
        
        // Merge dex files using d8 --merge
        if i > 0:
            args = ["d8", "--merge"]
            args.add("--output", chunkOutput)
            args.addAll(result.dexFiles)
            args.addAll(prevDexFiles)
            processRunner.run("d8", args)
        
        prevDexFiles = result.dexFiles
        memoryManager.releaseMemory()
    
    return mergeChunkResults(allChunks)
```

### 14.6 R8 Optimization (Release)

```
Input: Set<Path> classFiles, DexConfig config, Path proguardRules
Output: DexOutput

Algorithm:

Function optimizeWithR8(classFiles, config, proguardRules):
    args = ["r8"]
    args.add("--release")
    args.add("--min-api", config.minSdk.toString())
    args.add("--output", outputDir)
    args.add("--pg-conf", proguardRules)
    
    // Library jars (kept but not output)
    for lib in config.libraryJars:
        args.add("--lib", lib)
    
    // Class files to optimize
    args.addAll(classFiles.map { it.toString() })
    
    result = processRunner.run("r8", args, timeout = 600_000)
    
    if result.exitCode != 0:
        throw DexException("R8 failed: " + result.stderr)
    
    dexFiles = sorted(listFiles(outputDir, "*.dex"))
    mappingFile = outputDir + "/mapping.txt"
    
    return DexOutput(
        dexFiles = dexFiles,
        totalMethodCount = countMethodsInDex(dexFiles),
        dexFileCount = dexFiles.size(),
        mappingFile = mappingFile if exists(mappingFile) else null,
        isOptimized = true
    )
```

### 14.7 Edge Cases

| Case | Handling |
|------|----------|
| Single class file | Works normally; produces classes.dex |
| 0 class files | Error; no dex to produce |
| Method count > 1M | Multi-dex with up to N dex files (N * 64k) |
| Duplicate class in classpath | d8 handles duplicates (first wins); warn on mismatch |
| Java 17+ bytecode | d8 8.0+ supports up to Java 17 |
| Corrupted .class file | d8 reports error; HBE parses error and reports source file |
| d8 OOM | Kill process; increase chunk size? No, decrease; retry |

---

## 15. APK Packaging Workflow

### 15.1 Purpose

Assemble all build outputs into a valid APK file (ZIP archive with specific structure). Apply alignment and compression rules.

### 15.2 APK Structure Requirements

```
APK is a ZIP archive with:
- No Central Directory Encryption (must be ZIP 2.0 compatible)
- Local file headers must precede file data
- Files must be stored in a specific order:
  1. AndroidManifest.xml (must be first entry for alignment)
  2. classes.dex, classes2.dex, ...
  3. resources.arsc
  4. res/ (compiled resources)
  5. assets/
  6. lib/<abi>/
  7. META-INF/ (signing)
- 4-byte alignment for all entries (especially resources.arsc and dex files)
- Stored (no compression) for: dex, native libs
- Deflate compression for: resources, assets, manifest
```

### 15.3 Workflow: Package APK

```
Input: DexOutput dexOutput, ResourceBundle resources, 
       Path manifest, List<Path> nativeLibs, List<Path> assets,
       Path outputDir
Output: Path apkFile

Algorithm:

Function packageApk(dexOutput, resources, manifest, nativeLibs, assets, outputDir):
    apkFile = outputDir + "/app.apk"
    
    // Use ZipBuilder (streaming, alignment-aware)
    zip = new ZipBuilder(apkFile, alignment = 4)
    
    // 1. AndroidManifest.xml — first entry
    zip.addEntry("AndroidManifest.xml", manifest, compression = DEFLATED)
    
    // 2. classes.dex files — stored (no compression)
    for dex in dexOutput.dexFiles:
        entryName = "classes" + (dexIndex == 0 ? "" : dexIndex) + ".dex"
        zip.addEntry(entryName, dex, compression = STORED, alignment = 4)
    
    // 3. resources.arsc
    zip.addEntry("resources.arsc", resources.resourcesArsc, 
        compression = STORED, alignment = 4)
    
    // 4. res/ directory
    for file in walkFiles(resources.compiledResDirectories):
        entryName = "res/" + relativePath(file, resources.compiledResDirectories)
        zip.addEntry(entryName, file, compression = DEFLATED)
    
    // 5. assets/
    for file in assets:
        entryName = "assets/" + fileName(file)
        zip.addEntry(entryName, file, compression = DEFLATED)
    
    // 6. native libs
    for lib in nativeLibs:
        // lib path: lib/<abi>/lib<name>.so
        entryName = "lib/" + abiFor(lib) + "/" + fileName(lib)
        zip.addEntry(entryName, lib, compression = STORED)
    
    // 7. kotlin/ metadata
    // (if kotlin module metadata exists)
    
    zip.close()
    
    return apkFile
```

### 15.4 ZipBuilder Design

```java
public class ZipBuilder implements AutoCloseable {
    
    public ZipBuilder(Path outputFile, int alignment);
    
    // Add entry with automatic alignment padding
    public void addEntry(String name, Path sourceFile, Compression compression);
    public void addEntry(String name, Path sourceFile, Compression compression, int alignment);
    
    // Add entry from byte array
    public void addEntry(String name, byte[] data, Compression compression);
    
    // Set compression level (0-9)
    public void setCompressionLevel(int level);  // default: 6
    
    public void close();
}
```

Alignment is achieved by writing padding bytes before each entry to ensure the data starts at a multiple of `alignment` bytes from the start of the file.

### 15.5 zipalign

```
Function zipalignApk(apkFile):
    if exists("zipalign"):
        args = ["zipalign", "-p", "4", apkFile, alignedApkFile]
        processRunner.run("zipalign", args)
        return alignedApkFile
    else:
        // Align in-process
        alignInProcess(apkFile, 4)
        return apkFile
```

### 15.6 Edge Cases

| Case | Handling |
|------|----------|
| No native libs | Skip lib/ directory |
| No assets | Skip assets/ directory |
| Duplicate entry name | Error: two resources with same path in APK |
| Very large APK (>2GB) | ZIP64 format support (rare for APK, but handle) |
| Corrupted input files | Re-read from build cache; if still corrupted, fail build |

---

## 16. APK Signing Workflow

### 16.1 Purpose

Sign the APK with v1 (JAR signing), v2 (APK Signature Scheme v2), and/or v3 (APK Signature Scheme v3) schemes. Debug builds use an auto-generated debug keystore. Release builds use a user-provided keystore.

### 16.2 Signing Schemes

| Scheme | Introduced | Mandatory | Notes |
|--------|-----------|-----------|-------|
| v1 (JAR) | API 1 | No | Signs individual entries (META-INF/*) |
| v2 | API 24 | Yes for minSdk >= 24 | Signs entire APK before ZIP central directory |
| v3 | API 28 | No | Supports key rotation |

**Decision**: Always sign v2+ (the whole-file scheme). Also sign v1 for backward compatibility (minSdk < 24).

### 16.3 Workflow: Sign APK

```
Input: Path apkFile, SigningConfig config
Output: SignedApk

Algorithm:

Function signApk(apkFile, config):
    if config.type == "debug":
        keystore = getOrCreateDebugKeystore()
        keystorePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    else:
        keystore = config.keystorePath
        keystorePassword = config.keystorePassword
        keyAlias = config.keyAlias
        keyPassword = config.keyPassword
    
    // Use apksigner
    args = ["apksigner", "sign"]
    args.add("--ks", keystore)
    args.add("--ks-pass", "pass:" + keystorePassword)
    args.add("--ks-key-alias", keyAlias)
    args.add("--key-pass", "pass:" + keyPassword)
    
    // Enable schemes
    args.add("--v1-signing-enabled", "true")
    args.add("--v2-signing-enabled", "true")
    args.add("--v3-signing-enabled", "true")
    
    args.add(apkFile)
    
    result = processRunner.run("apksigner", args, timeout = 60_000)
    
    if result.exitCode != 0:
        throw SigningException("apksigner failed: " + result.stderr)
    
    // Verify
    verifyResult = processRunner.run("apksigner", 
        ["verify", "--verbose", apkFile])
    
    return SignedApk(
        apkPath = apkFile,
        v1Signed = true,
        v2Signed = true,
        v3Signed = true,
        sizeBytes = fileSize(apkFile)
    )
```

### 16.4 Debug Keystore Auto-Generation

```
Function getOrCreateDebugKeystore():
    keystorePath = hbeHome + "/debug.keystore"
    
    if exists(keystorePath):
        return keystorePath
    
    // Generate using keytool
    args = ["keytool", "-genkey", "-v"]
    args.add("-keystore", keystorePath)
    args.add("-alias", "androiddebugkey")
    args.add("-keyalg", "RSA")
    args.add("-keysize", "2048")
    args.add("-validity", "10000")
    args.add("-dname", "CN=Android Debug, O=Android, C=US")
    args.add("-storepass", "android")
    args.add("-keypass", "android")
    
    processRunner.run("keytool", args)
    
    return keystorePath
```

### 16.5 Alternative: Pure-Java Signing

If `apksigner` is unavailable, use the `jarsigner` + manual v2 signer:

- `jarsigner` for v1 signing (built into JDK)
- Custom v2/v3 signer implementation using `java.security` APIs
- The v2 signer adds an `APK Signing Block` before the central directory

Implementation complexity is high — `apksigner` is strongly preferred.

### 16.6 Edge Cases

| Case | Handling |
|------|----------|
| No signing requested | Produce unsigned APK (for testing only) |
| Debug keystore missing | Auto-generate on first build |
| Release keystore wrong password | Error with clear message; do not retry |
| APK already signed | apksigner handles re-signing (overwrites) |
| apksigner not found | Download build-tools; fail if download fails |
| v3 signing not supported | Fall back to v2 only (minSdk < 28) |

---

## 17. Incremental Build Algorithm

### 17.1 Purpose

Avoid re-executing phases whose inputs have not changed since the last successful build. The goal is 80%+ phase skipping on rebuilds with small changes.

### 17.2 Principle

For each phase, if we can prove that all inputs are identical to the previous build, the phase output is identical, and we can skip execution. The proof is a cryptographic hash of all inputs.

### 17.3 Algorithm: Incremental Build

```
Input: BuildRequest request, BuildGraph graph
Output: ExecutionPlan (with skipping decisions)

Algorithm:

Function computeSkippable(graph, previousBuildState):
    for node in graph.nodes:
        if not node.phase.isCacheable():
            node.decision = EXECUTE
            continue
        
        inputHash = computeInputHash(node)
        cacheKey = ArtifactKey(node.phase, inputHash, node.variant)
        
        if cacheManager.exists(cacheKey):
            // Previous output is still valid
            node.decision = SKIP
            node.cacheKey = cacheKey
        else:
            node.decision = EXECUTE

Function computeInputHash(node):
    hashInputs = []
    
    // Hash all direct input files
    for inputPath in node.phase.getInputFiles():
        hashInputs.add(sha256(inputPath))
    
    // Hash configuration
    hashInputs.add(sha256(node.phase.getConfig()))
    
    // Hash tool versions
    hashInputs.add(node.phase.getToolVersion())
    
    // Hash dependency outputs (recursive, but bounded)
    for dep in graph.getDependencies(node):
        if dep.outputHash != null:
            hashInputs.add(dep.outputHash)
    
    return sha256(join(hashInputs))
```

### 17.4 Per-Phase Incremental Strategy

| Phase | Inputs Tracked | Cache Granularity |
|-------|---------------|-------------------|
| SDK Resolve | SDK version, platform version | By API level + build-tools version |
| Dep Resolve | `hbe.json` dependencies field, repo list | By dependency set hash |
| Manifest Merge | All manifest files | Per manifest file |
| Resource Compile | Each resource file | Per `.flat` file |
| Resource Link | All `.flat` hashes, manifest, dependencies | Full link output |
| Source Compile (Java) | Each `.java` file, classpath hashes | Per batch of 50 files |
| Source Compile (Kotlin) | Each `.kt` file, classpath hashes | Per batch of 30 files |
| DEX | All class file hashes | Per chunk of 200 files |
| R8 | All class files, proguard rules | Full output |
| Package | All input artifact hashes | Per variant |
| Sign | APK hash, signing config | Per variant |

### 17.5 Source-Level Incremental Compilation

For source compilation, detect which files changed:

```
Function getChangedSources(projectDir, previousState):
    currentHashes = scanSourceFiles(projectDir)
    
    changed = []
    for file, hash in currentHashes:
        if previousState.fileHashes[file] != hash:
            changed.add(file)
    
    for file in previousState.fileHashes.keys():
        if not exists(file):
            changed.add(file)  // deleted
    
    return changed
```

### 17.6 Change Propagation

When source file A changes:
1. Source compilation re-executes for file A (and batch containing A)
2. If A's public API changed (method signatures, class declarations), dependent files must also recompile
3. DEX phase re-executes if any class files changed
4. Package phase re-executes if any dex files changed

**Dependency tracking** (future improvement):
- Parse imports at compile time
- Build dependency graph: `File A → File B (depends on)`
- On change, find all transitively affected files
- Recompile only the minimal set

### 17.7 State Persistence

Incremental state stored in SQLite:

```sql
CREATE TABLE build_state (
    project_id TEXT NOT NULL,
    build_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (project_id, build_id)
);

CREATE TABLE file_hashes (
    project_id TEXT NOT NULL,
    build_id TEXT NOT NULL,
    file_path TEXT NOT NULL,
    sha256_hash TEXT NOT NULL,
    last_modified INTEGER NOT NULL,
    PRIMARY KEY (project_id, build_id, file_path)
);

CREATE TABLE phase_hashes (
    project_id TEXT NOT NULL,
    build_id TEXT NOT NULL,
    phase_name TEXT NOT NULL,
    input_hash TEXT NOT NULL,
    output_hash TEXT NOT NULL,
    cached INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, build_id, phase_name)
);
```

### 17.8 Cache Hit Ratio Target

| Phase | Target Hit Rate (2nd build, 1 file changed) |
|-------|---------------------------------------------|
| SDK Resolve | 100% |
| Dep Resolve | 100% |
| Manifest Merge | 100% (if manifest unchanged) |
| Resource Compile | 99% (only changed file recompiled) |
| Resource Link | 0% (must re-link even if 1 file changes — ID shift) |
| Source Compile | 90%+ (only changed file + its dependents) |
| DEX | 0% (must re-dex if any class changed) |
| Package | 0% (must repackage if any input changed) |


---

## 18. Cache Architecture

### 18.1 Purpose

Provide fast, reliable, storage-efficient caching of build artifacts to enable incremental builds. The cache is content-addressable and uses SQLite for metadata indexing.

### 18.2 Cache Layers

```
┌──────────────────────────────────────┐
│          CacheManager (API)          │
│  get(key) │ put(key, artifact)       │
│  invalidate(key) │ evict(maxBytes)   │
└──────────┬───────────────────────────┘
           │
┌──────────▼───────────────────────────┐
│      CachePolicy (eviction, TTL)     │
│  LRU tracker │ maxSize enforcement   │
└──────────┬───────────────────────────┘
           │
┌──────────▼───────────────────────────┐
│    CacheBackend (pluggable)          │
│  SQLiteBackend │ FsBackend           │
└──────────────────────────────────────┘
```

### 18.3 Cache Key Structure

```
CacheKey = SHA-256(
    phase_name +
    project_hash +
    variant +
    input_file_hashes (sorted) +
    config_hash +
    tool_versions +
    dependency_artifact_hashes
)

String representation: hex(SHA-256) → "a1b2c3d4..."
```

### 18.4 SQLite Backend Schema

```sql
-- Main cache table
CREATE TABLE cache_entries (
    cache_key TEXT PRIMARY KEY,
    phase_name TEXT NOT NULL,
    project_id TEXT NOT NULL,
    variant TEXT NOT NULL,
    created_at INTEGER NOT NULL,            -- epoch ms
    last_accessed_at INTEGER NOT NULL,
    access_count INTEGER NOT NULL DEFAULT 1,
    size_bytes INTEGER NOT NULL,
    artifact_path TEXT NOT NULL,             -- relative path in cache store
    content_hash TEXT NOT NULL,              -- SHA-256 of the artifact itself
    metadata TEXT                            -- JSON blob (tool versions, input count, etc.)
);

-- Index for LRU eviction
CREATE INDEX idx_last_accessed ON cache_entries(last_accessed_at);

-- Index for project-based invalidation
CREATE INDEX idx_project ON cache_entries(project_id, phase_name);

-- Index for size queries
CREATE INDEX idx_size ON cache_entries(size_bytes);

-- Cache statistics
CREATE TABLE cache_stats (
    stat_name TEXT PRIMARY KEY,
    stat_value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
```

### 18.5 Cache Storage Layout

```
~/.hbe/cache/
├── hbe-cache.db                  # SQLite database (metadata + indexing)
├── artifacts/
│   ├── a1/
│   │   ├── b2c3d4e5f6...        # artifact file (named by cache key)
│   │   └── a1b2c3d4e5...        # another artifact
│   ├── f7/
│   │   └── ...
│   └── ...                       # 2-character shard for directory scalability
└── tmp/                          # temporary download/extraction space
```

Two-character sharding prevents any directory from containing more than ~256 entries at the top level, even with millions of cached artifacts.

### 18.6 Cache Operations

```java
public class CacheManager {
    
    public Optional<Path> get(ArtifactKey key) {
        // 1. Look up in SQLite
        // 2. Check artifact file exists on disk
        // 3. Verify content hash matches
        // 4. Update last_accessed_at, access_count
        // 5. Return artifact path (read-only)
    }
    
    public void put(ArtifactKey key, Path artifactFile) {
        // 1. Compute content hash of artifact
        // 2. Choose shard directory (first 2 chars of key)
        // 3. Copy artifact to shard directory
        // 4. Insert/update SQLite entry
        // 5. Update access time
        // 6. Check if eviction needed
    }
    
    public void invalidate(ArtifactKey key) {
        // Remove from SQLite, delete artifact file
    }
    
    public void invalidateProject(String projectId) {
        // Remove all entries for this project
    }
    
    public void evict(long targetBytes) {
        // 1. Compute current cache size (from SQLite)
        // 2. If size > maxSize, evict LRU entries until size < maxSize * 0.8
        // 3. Delete artifact files + remove from SQLite
    }
}
```

### 18.7 Eviction Policy

**Policy**: LRU (Least Recently Used) with size awareness.

```
Algorithm:
1. Target: reduce cache to 80% of maxSize
2. Query: SELECT cache_key FROM cache_entries 
          ORDER BY last_accessed_at ASC 
          LIMIT 1000
3. For each entry, delete artifact + remove from DB
4. Repeat until total_size < maxSize * 0.8
5. Never evict entries younger than 1 hour (protect fresh builds)

Additional rules:
- If free disk space < cacheMinFreeStorageBytes (500MB), 
  evict aggressively until free space > 1GB
- Entries for SDK tools (platforms, build-tools) are never evicted
- AAR cache entries are evicted only when their version is no longer referenced
```

### 18.8 Cache Statistics

```java
public class CacheStats {
    long totalEntries;
    long totalSizeBytes;
    long maxSizeBytes;
    long hitCount;           // lifetime
    long missCount;          // lifetime
    double hitRate;          // hitCount / (hitCount + missCount)
    double avgAccessTimeMs;
    int evictionCount;       // lifetime
    int shardCount;
    Map<String, Long> sizeByPhase;  // per-phase breakdown
}
```

### 18.9 Considerations

| Concern | Design |
|---------|--------|
| Concurrent access | SQLite WAL mode for concurrent reads; write lock for mutations |
| File system limits | Single directory with 10M files would fail; sharding prevents this |
| Corruption | Content hash verification on reads; periodic full verification |
| Atomic writes | Write to tmp file, rename to final location |
| Portable | All paths relative; cache can be moved between machines |
| Cleanup | `hbe cache --prune` for manual cleanup |

---

## 19. Cache Invalidation Algorithm

### 19.1 Purpose

Determine when a cached artifact is stale and must be re-generated. Staleness can result from source changes, tool version changes, configuration changes, or dependency updates.

### 19.2 Invalidation Triggers

| Trigger | Detection Method | Action |
|---------|-----------------|--------|
| Source file changed | File hash different from stored hash | Invalidate affected phases |
| Source file added/removed | File listing different from stored listing | Invalidate compile phases |
| Config changed (hbe.json) | Config hash different | Invalidate all phases |
| SDK version changed | SDK version changed in config | Invalidate compile + later phases |
| Build tool version changed | Tool hash different | Invalidate all phases |
| Dependency version changed | Dependency list hash different | Invalidate resolve + compile + later |
| Manual clean | `hbe clean` | Invalidate entire project |
| Cache corruption | Content hash mismatch | Invalidate single entry |
| Time-based expiration | TTL exceeded | Invalidate (future: nightlies) |

### 19.3 Algorithm: Full Invalidation Check

```
Input: BuildGraph graph, CacheManager cache
Output: Set<BuildNode> invalidatedNodes

Algorithm:

Function checkInvalidation(graph):
    invalidated = new HashSet<BuildNode>()
    
    for node in topologicalSort(graph.nodes):
        if node in invalidated:
            continue  // already invalidated by dependency
        
        // Compute current input hash
        currentHash = computeInputHash(node)
        
        // Check cache
        cacheResult = cache.get(node.cacheKey)
        
        if cacheResult.isEmpty():
            // No cached artifact → must execute
            invalidated.add(node)
        elif cacheResult.getHash() != currentHash:
            // Input hash changed → must execute
            invalidated.add(node)
        else:
            // Artifact valid → skip node
            
        // If this node is invalidated, all downstream nodes 
        // that consume its output must also be invalidated
        if node in invalidated:
            downstream = graph.getConsumers(node)
            invalidated.addAll(downstream)
    
    return invalidated
```

### 19.4 Cascade Invalidation

```
Example: Changing a single line in MainActivity.kt:

1. File hash for MainActivity.kt changes
2. Source compile node for the Kotlin batch containing MainActivity 
   is invalidated
3. DEX node is invalidated (consumes class files)
4. Package node is invalidated (consumes dex)
5. Sign node is invalidated (consumes APK)

Everything before source compile (SDK, deps, resources) stays cached.
```

### 19.5 Conservative vs Aggressive Invalidation

**Conservative** (default): Invalidate downstream nodes whenever any input changes, even if the output would be identical.

**Aggressive** (future): For source compilation, use AST comparison to determine whether public API changed. If only method bodies changed (not signatures), reuse cached `.class` files and only recompile the changed files.

### 19.6 Time-Based Invalidations

```
// Session-scoped cache (invalidated on daemon restart)
sessionCache: tool detection, SDK paths, JDK detection

// Build-scoped cache (invalidated per build)
buildCache: temporary extraction directories

// Persistent cache (invalidated by content hash)
persistentCache: compiled classes, dex files, resources

// Long-lived cache (manually invalidated)
longLivedCache: downloaded SDK platforms, build-tools, AARs
```

---

## 20. Memory Management Strategy

### 20.1 Purpose

Ensure builds complete within the available RAM budget (default: 1024MB) without OOM. Achieve this through process isolation, batched execution, and explicit memory release between phases.

### 20.2 Architecture

```
MemoryManager
├── RamMonitor — reads /proc/meminfo or ActivityManager.MemoryInfo
├── BudgetAllocator — assigns per-phase budgets
├── ProcessKiller — kills finished/spinning processes
└── Throttler — limits batch size based on budget
```

### 20.3 RAM Monitoring

```java
public class RamMonitor {
    
    public long getAvailableMemoryBytes() {
        // Android: ActivityManager.getMemoryInfo()
        // Linux: /proc/meminfo → MemAvailable + Cached + Buffers
        // Fallback: Runtime.getRuntime().maxMemory() 
        //           - (totalMemory() - freeMemory())
    }
    
    public long getTotalMemoryBytes() {
        // /proc/meminfo → MemTotal
    }
    
    public boolean isLowMemory() {
        return getAvailableMemoryBytes() < LOW_MEMORY_THRESHOLD;  // 512MB
    }
    
    public MemoryPressure getPressure() {
        // NONE: > 40% available
        // MODERATE: 20-40%
        // CRITICAL: < 20%
    }
}
```

### 20.4 Budget Allocation

```
Function allocateBudget(phase, availableRam):
    // Fixed overhead for HBE itself: 128MB
    reserved = 128 * 1024 * 1024
    
    availableForPhase = availableRam - reserved
    
    // Cap by phase max
    phaseMax = phase.estimateMemoryMb() * 1024 * 1024
    
    allocated = min(availableForPhase, phaseMax)
    
    return allocated

Function computeBatchSize(phase, allocated):
    if phase is JavaCompile:
        perProcessOverhead = 50 * 1024 * 1024  // 50MB JVM overhead
        perFileCost = 5 * 1024 * 1024          // 5MB per source file
        return max(1, (allocated - perProcessOverhead) / perFileCost)
    
    if phase is KotlinCompile:
        perProcessOverhead = 80 * 1024 * 1024  // 80MB JVM overhead
        perFileCost = 12 * 1024 * 1024         // 12MB per source file
        return max(1, (allocated - perProcessOverhead) / perFileCost)
    
    if phase is Dex:
        perProcessOverhead = 60 * 1024 * 1024
        perClassFileCost = 256 * 1024         // 256KB per class file
        return max(1, (allocated - perProcessOverhead) / perClassFileCost)
```

### 20.5 Process Isolation

```
Function runWithBudget(command, args, budgetBytes):
    // Build JVM -Xmx argument
    jvmArgs = ["-Xmx" + (budgetBytes * 0.8 / 1024 / 1024) + "m"]
    
    // For javac:
    args = [findJava(), jvmArgs, "-jar", findJavac(), ...args]
    
    // Start process
    process = new ProcessBuilder(args).start()
    
    // Monitor RAM (optional, for diagnostics)
    // PID → /proc/pid/status → VmRSS
    
    // Timeout
    if (!process.waitFor(timeout, MILLISECONDS)):
        process.destroyForcibly()
        throw TimeoutException()
    
    return process.exitCode
```

### 20.6 Cross-Phase Memory Release

```
Function releaseMemory():
    // 1. Suggest GC
    System.gc()
    System.runFinalization()
    
    // 2. Wait for GC (non-blocking hint)
    Thread.sleep(50)
    
    // 3. Kill any lingering processes
    killOrphanProcesses()
    
    // 4. Log freed memory
    freed = previousFree - getAvailableMemory()
    logger.debug("Memory released", { "freed_mb": freed / 1024 / 1024 })
```

### 20.7 Memory Pressure Response

| Pressure Level | Action |
|----------------|--------|
| NONE (>40% free) | Normal operation |
| MODERATE (20-40% free) | Reduce batch sizes by 25%; skip non-critical phases |
| CRITICAL (<20% free) | Sequential execution only; minimum batch size (1); abort if still OOM |

### 20.8 Edge Cases

| Case | Handling |
|------|----------|
| RAM drops below 256MB during phase | Cancel phase; report OOM error with suggestion |
| Process exceeds budget (RSS > Xmx) | Process killed by kernel OOM; HBE detects via exit code |
| Device has 1.5GB available | Use absolute minimum batch size (1 file per batch) |
| Device has 8GB available | Increase batch sizes 3x; enable parallel phases |

---

## 21. Low RAM Optimization Strategy

### 21.1 Target

Complete a medium-sized Android project (50 source files, 50 dependencies, Compose) on a device with 4GB total RAM (~2.5GB available to HBE).

### 21.2 Strategy Summary

| Tactic | RAM Saved | Complexity |
|--------|-----------|------------|
| Sequential phase execution | ~60% peak reduction | Low |
| Batched compilation | ~40% peak reduction | Medium |
| Process-based isolation | ~30% peak reduction | Low |
| Explicit GC + process kill | ~20% peak reduction | Low |
| Streaming AAR extraction | ~10% peak reduction | Medium |
| Lazy dependency resolution | ~15% peak reduction | Medium |
| Dex chunking | ~30% peak reduction | High |

### 21.3 RAM Budget Allocation (4GB Device)

```
Total RAM:      4096 MB
OS overhead:   -1500 MB (Android + services)
Available:      2596 MB
HBE overhead:  -128 MB (engine process)
Phase budget:   2468 MB

Budget distribution:
  javac batch:      256 MB (1 at a time)
  kotlinc batch:    384 MB (1 at a time)
  d8 chunk:         256 MB (1 at a time)
  aapt2 link:       256 MB (1 at a time)
  R8:               512 MB (1 at a time, release only)
  Reserved:         804 MB (for caching, OS, other processes)
```

### 21.4 Build Speed on 4GB Target

Estimated build times (medium app, 50 source files, first build):

| Phase | Time |
|-------|------|
| SDK Resolve | 20-40s (first time, network) |
| Dep Resolve | 15-30s (first time, network) |
| Resource Compile | 5-10s |
| Resource Link | 3-5s |
| Source Compile (b 50 files, b 30) | 60-120s |
| DEX | 15-30s |
| Package | 2-5s |
| Sign | 3-5s |
| **Total** | **~120-240s (2-4 min)** |

Incremental build (1 file changed): ~30-60s

### 21.5 Comparison: 4GB vs 8GB vs 16GB

| Metric | 4GB | 8GB | 16GB |
|--------|-----|-----|------|
| Batch size (Java) | 50 | 200 | 500 |
| Batch size (Kotlin) | 30 | 100 | 250 |
| DEX chunk size | 200 | 1000 | all |
| Parallel phases | No | Partial | Yes |
| Full build time | 180s | 90s | 60s |
| Incremental build | 45s | 25s | 15s |

### 21.6 Adaptive Strategy

The engine detects available RAM and tunes parameters automatically:

```
Function autoTune(availableRamMb):
    if availableRamMb >= 4096:   // Tablet / high-end phone
        batchSizeJava = 500
        batchSizeKotlin = 250
        dexChunkSize = Integer.MAX_VALUE
        enableParallel = true
    
    elif availableRamMb >= 2048:  // 4GB phone (2GB usable)
        batchSizeJava = 100
        batchSizeKotlin = 60
        dexChunkSize = 500
        enableParallel = false
    
    elif availableRamMb >= 1024:  // 2GB phone (1GB usable)
        batchSizeJava = 30
        batchSizeKotlin = 15
        dexChunkSize = 100
        enableParallel = false
    
    else:                          // Very constrained
        batchSizeJava = 5
        batchSizeKotlin = 3
        dexChunkSize = 50
        enableParallel = false
```

---

## 22. Project Detection Algorithm

### 22.1 Purpose

Analyze a directory to determine what kind of Android project it is, extract build configuration, locate source files, and produce a `ProjectModel` that the build graph builder can use.

### 22.2 Supported Project Types

| Type | Detection | Description |
|------|-----------|-------------|
| HBE native | `hbe.json` exists | Native HBE project format |
| Gradle | `build.gradle` or `build.gradle.kts` exists | Standard Android/Gradle project |
| AndroidIDE | `project.json` + `build.gradle` | AndroidIDE project |
| AOSP | `Android.mk` or `Android.bp` | Android Open Source Project module |
| Raw | Has `AndroidManifest.xml` + `src/` or `java/` | Unconfigured Android project |

### 22.3 Algorithm: Detect Project Type

```
Input: Path projectDir
Output: ProjectModel

Algorithm:

Function detectProject(projectDir):
    projectModel = new ProjectModel()
    projectModel.root = projectDir
    
    // Step 1: Check for explicit project file
    if exists(projectDir + "/hbe.json"):
        projectModel.type = ProjectType.HBE
        projectModel.config = parseHbeJson(projectDir + "/hbe.json")
    
    elif exists(projectDir + "/build.gradle.kts"):
        projectModel.type = ProjectType.GRADLE_KTS
        projectModel.config = parseGradleKts(projectDir + "/build.gradle.kts")
    
    elif exists(projectDir + "/build.gradle"):
        projectModel.type = ProjectType.GRADLE
        projectModel.config = parseGradle(projectDir + "/build.gradle")
    
    elif exists(projectDir + "/AndroidManifest.xml"):
        projectModel.type = ProjectType.RAW
        projectModel.config = inferFromManifest(projectDir + "/AndroidManifest.xml")
    
    else:
        throw UnknownProjectException(projectDir)
    
    // Step 2: Locate source directories
    projectModel.sourceDirs = findSourceDirs(projectDir)
    // Searches: src/, src/main/java/, src/main/kotlin/, java/, kotlin/
    
    // Step 3: Locate resource directory
    projectModel.resDir = findResourceDir(projectDir)
    // Searches: res/, src/main/res/
    
    // Step 4: Locate assets directory
    projectModel.assetsDir = findAssetsDir(projectDir)
    
    // Step 5: Locate AndroidManifest.xml
    projectModel.manifest = findManifest(projectDir)
    
    // Step 6: Extract SDK versions
    projectModel.compileSdk = projectModel.config.compileSdk ?: 34
    projectModel.minSdk = projectModel.config.minSdk ?: extractFromManifest(projectModel.manifest)
    projectModel.targetSdk = projectModel.config.targetSdk ?: extractFromManifest(projectModel.manifest)
    
    // Step 7: Extract dependencies
    projectModel.dependencies = projectModel.config.dependencies ?: []
    
    // Step 8: Extract signing config
    projectModel.signingConfig = projectModel.config.signing ?: SigningConfig.debug()
    
    return projectModel
```

### 22.4 Source Directory Detection

```
Function findSourceDirs(projectDir):
    candidates = [
        projectDir + "/src/main/java",
        projectDir + "/src/main/kotlin",
        projectDir + "/src/java",
        projectDir + "/src/kotlin",
        projectDir + "/src",
        projectDir + "/java",
        projectDir + "/kotlin",
        projectDir + "/app/src/main/java",
        projectDir + "/app/src/main/kotlin",
        projectDir + "/app/src/java",
        projectDir + "/app/src/kotlin",
    ]
    
    return candidates.filter { exists(it) }
```

### 22.5 Multi-Module Detection

```
Function findModules(projectDir):
    modules = [projectModel]  // root is always a module
    
    // Search for sub-projects
    // Gradle: settings.gradle.kts → include(":library")
    // HBE: hbe.json → modules: [{"path": "library"}]
    
    for subdir in projectDir.listDirectories():
        if exists(subdir + "/build.gradle") 
           or exists(subdir + "/hbe.json")
           or exists(subdir + "/AndroidManifest.xml"):
            modules.add(detectProject(subdir))
    
    return modules
```

---

## 23. Android Studio Compatibility Layer

### 23.1 Purpose

Allow HBE to build projects created in Android Studio with minimal reconfiguration. This is a **best-effort** compatibility layer, not a full Gradle replacement.

### 23.2 Supported Gradle Configurations

| Feature | Support | Notes |
|---------|---------|-------|
| `compileSdk` | ✅ Read from build.gradle.kts | |
| `minSdk` / `targetSdk` | ✅ | |
| `dependencies` block | ✅ Basic support | `implementation`, `api`, `compileOnly` |
| `plugins` (Android, Kotlin) | ✅ | Plugin versions extracted |
| `android.defaultConfig` | ✅ | |
| `android.buildTypes` | ✅ debug/release | |
| `android.productFlavors` | ❌ v1 | Planned for v2 |
| `android.viewBinding` | ⚠️ Partial | Requires KSP |
| `android.dataBinding` | ❌ | Not planned |
| `buildFeatures.compose` | ✅ | Enables compose plugin |
| `compileOptions` | ✅ Java version | |
| `kotlinOptions` | ✅ JVM target | |
| `ndkVersion` | ✅ | |
| `aaptOptions` | ❌ | Rarely used |
| `lintOptions` | N/A | Not a build concern |
| Custom tasks | ❌ | Not supported; HBE is declarative |

### 23.3 Gradle Parser (KTS)

```
Input: Path buildGradleKts
Output: BuildConfig (SDK versions, deps, plugins)

Algorithm:

Function parseGradleKts(buildFile):
    // NOT a full Kotlin DSL parser — that would be AST parsing.
    // Instead, use regex-based extraction for well-known patterns.
    
    config = new BuildConfig()
    
    // Extract compileSdk
    match = REGEX_COMPILE_SDK.find(fileContent)
    if match: config.compileSdk = parseInt(match.group("value"))
    
    // Extract minSdk, targetSdk
    match = REGEX_MIN_SDK.find(fileContent)
    if match: config.minSdk = parseInt(match.group("value"))
    
    match = REGEX_TARGET_SDK.find(fileContent)
    if match: config.targetSdk = parseInt(match.group("value"))
    
    // Extract dependencies
    for match in REGEX_DEPENDENCY.findAll(fileContent):
        config.dependencies.add(match.group("coord"))
    
    // Extract plugins
    for match in REGEX_PLUGIN.findAll(fileContent):
        config.plugins.add(match.group("id"))
    
    // Extract compose flag
    if "compose" in fileContent and "true" in fileContent:
        config.compose = true
    
    return config
```

Regex-based parsing has known limitations (conditional blocks, extension functions) but covers 80%+ of real-world Android projects.

### 23.4 Default Values When Parsing Fails

| Field | Default |
|-------|---------|
| compileSdk | 34 |
| minSdk | 21 |
| targetSdk | 34 |
| buildToolsVersion | latest |
| sourceCompatibility | Java 17 |
| compose | false (auto-detect Compose dependency) |
| versionCode | 1 |
| versionName | "1.0" |

### 23.5 Limitations

Projects that use complex Gradle features will not parse correctly:
- Custom Gradle tasks
- BuildSrc plugins
- Convention plugins
- Version catalogs (`libs.versions.toml`)
- Dynamic versions (`+`)
- BuildScript code blocks

These projects require an `hbe.json` configuration file for full HBE support. The compatibility layer provides a reasonable first build attempt.

---

## 24. AndroidIDE Compatibility Layer

### 24.1 Purpose

AndroidIDE is an Android app that provides a mobile IDE experience using Gradle. HBE detects AndroidIDE project structure and extracts build configuration.

### 24.2 AndroidIDE Project Structure

```
project/
├── project.json           # AndroidIDE project config
├── build.gradle.kts       # Standard Gradle build
├── app/
│   ├── build.gradle.kts
│   └── src/
│       └── main/
│           ├── java/
│           ├── res/
│           └── AndroidManifest.xml
└── settings.gradle.kts
```

### 24.3 Detection

```
Function detectAndroidIDE(projectDir):
    return exists(projectDir + "/project.json")

Function parseProjectJson(projectDir):
    json = parseJson(projectDir + "/project.json")
    
    // Extract:
    packageName = json["packageName"]
    projectName = json["projectName"]
    // Java path, resource path, etc.
    
    return AndroidIDEProject(
        packageName = packageName,
        projectName = projectName
    )
```

The AndroidIDE layer then delegates to the Gradle compatibility layer for build configuration.


---

## 25. HBE Project Format

### 25.1 Purpose

The native HBE project configuration format. A single `hbe.json` file in the project root defines everything needed to build.

### 25.2 File: `hbe.json`

```json
{
  "$schema": "https://schemas.hbe.io/hbe-project-v1.json",
  "name": "MyApp",
  "version": "1.0.0",
  "type": "application",
  
  "android": {
    "compileSdk": 34,
    "minSdk": 24,
    "targetSdk": 34,
    "buildToolsVersion": "34.0.0",
    "ndkVersion": "26.1.10909125"
  },
  
  "buildTypes": {
    "debug": {
      "debuggable": true,
      "compose": true
    },
    "release": {
      "debuggable": false,
      "minify": true,
      "proguardRules": "proguard-rules.pro"
    }
  },
  
  "sourceSets": {
    "main": {
      "java": ["src/main/java"],
      "kotlin": ["src/main/kotlin"],
      "res": "src/main/res",
      "assets": "src/main/assets",
      "manifest": "src/main/AndroidManifest.xml",
      "nativeLibs": "src/main/jniLibs"
    }
  },
  
  "dependencies": {
    "repositories": [
      "https://dl.google.com/dl/android/maven2/",
      "https://repo1.maven.org/maven2/"
    ],
    "implementation": [
      "androidx.appcompat:appcompat:1.6.1",
      "androidx.core:core-ktx:1.12.0",
      "androidx.lifecycle:lifecycle-runtime-ktx:2.7.0"
    ],
    "compileOnly": [
      "androidx.annotation:annotation:1.7.0"
    ],
    "annotationProcessor": [
      "com.google.dagger:dagger-compiler:2.48"
    ]
  },
  
  "signing": {
    "debug": {
      "keystore": null,
      "autoGenerate": true
    },
    "release": {
      "keystore": "release.keystore",
      "keystorePassword": null,
      "keyAlias": "mykey",
      "keyPassword": null
    }
  },
  
  "options": {
    "javaVersion": "17",
    "kotlinVersion": "1.9.22",
    "compose": true,
    "composeCompilerVersion": "1.5.8",
    "dataBinding": false,
    "viewBinding": false
  },
  
  "modules": [
    {
      "path": "library",
      "type": "library"
    }
  ]
}
```

### 25.3 JSON Schema (hbe.json)

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "HBE Project Configuration",
  "type": "object",
  "required": ["name", "type"],
  "properties": {
    "name": { "type": "string", "description": "Project name" },
    "version": { "type": "string", "description": "Project version", "default": "1.0.0" },
    "type": { "type": "string", "enum": ["application", "library", "dynamic-feature"] },
    
    "android": {
      "type": "object",
      "properties": {
        "compileSdk": { "type": "integer", "minimum": 21, "default": 34 },
        "minSdk": { "type": "integer", "minimum": 14, "default": 24 },
        "targetSdk": { "type": "integer", "minimum": 14, "default": 34 },
        "buildToolsVersion": { "type": "string" },
        "ndkVersion": { "type": "string" }
      }
    },
    
    "buildTypes": {
      "type": "object",
      "properties": {
        "debug": { "$ref": "#/definitions/buildType" },
        "release": { "$ref": "#/definitions/buildType" }
      }
    },
    
    "sourceSets": {
      "type": "object",
      "patternProperties": {
        "^[a-zA-Z_][a-zA-Z0-9_]*$": { "$ref": "#/definitions/sourceSet" }
      }
    },
    
    "dependencies": {
      "type": "object",
      "properties": {
        "repositories": { "type": "array", "items": { "type": "string", "format": "uri" } },
        "implementation": { "type": "array", "items": { "type": "string" } },
        "api": { "type": "array", "items": { "type": "string" } },
        "compileOnly": { "type": "array", "items": { "type": "string" } },
        "annotationProcessor": { "type": "array", "items": { "type": "string" } }
      }
    },
    
    "signing": {
      "type": "object",
      "properties": {
        "debug": { "$ref": "#/definitions/signingConfig" },
        "release": { "$ref": "#/definitions/signingConfig" }
      }
    },
    
    "options": {
      "type": "object",
      "properties": {
        "javaVersion": { "type": "string", "enum": ["11", "17", "21"], "default": "17" },
        "kotlinVersion": { "type": "string" },
        "compose": { "type": "boolean", "default": false },
        "composeCompilerVersion": { "type": "string" },
        "viewBinding": { "type": "boolean", "default": false }
      }
    },
    
    "modules": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["path"],
        "properties": {
          "path": { "type": "string" },
          "type": { "type": "string", "enum": ["application", "library", "dynamic-feature"] }
        }
      }
    }
  },
  
  "definitions": {
    "buildType": {
      "type": "object",
      "properties": {
        "debuggable": { "type": "boolean", "default": true },
        "minify": { "type": "boolean", "default": false },
        "proguardRules": { "type": "string" },
        "compose": { "type": "boolean", "default": false }
      }
    },
    "sourceSet": {
      "type": "object",
      "properties": {
        "java": { "type": "array", "items": { "type": "string" } },
        "kotlin": { "type": "array", "items": { "type": "string" } },
        "res": { "type": "string" },
        "assets": { "type": "string" },
        "manifest": { "type": "string" },
        "nativeLibs": { "type": "string" }
      }
    },
    "signingConfig": {
      "type": "object",
      "properties": {
        "keystore": { "type": ["string", "null"] },
        "autoGenerate": { "type": "boolean", "default": false },
        "keystorePassword": { "type": ["string", "null"] },
        "keyAlias": { "type": ["string", "null"] },
        "keyPassword": { "type": ["string", "null"] }
      }
    }
  }
}
```

### 25.4 Minimal hbe.json

```json
{
  "name": "MyApp",
  "type": "application",
  "android": { "compileSdk": 34 },
  "sourceSets": {
    "main": {
      "java": ["src"],
      "res": "res",
      "manifest": "AndroidManifest.xml"
    }
  }
}
```

### 25.5 Project Structure Convention

If no `hbe.json` exists, HBE uses a conventional layout:

```
project/
├── AndroidManifest.xml
├── src/
│   ├── main/
│   │   ├── java/         (or kotlin/)
│   │   ├── res/
│   │   └── assets/
│   └── test/
├── libs/                  (manual jar/aar dependencies)
├── hbe.json               (optional)
├── proguard-rules.pro      (optional)
└── local.properties        (optional SDK path override)
```

---

## 26. Configuration Format and JSON Schemas

### 26.1 Engine Configuration: `~/.hbe/config.json`

```json
{
  "sdk": {
    "path": "/custom/sdk/path",
    "autoDownload": true,
    "preferredVersion": {
      "buildTools": "34.0.0",
      "platform": 34
    }
  },
  "cache": {
    "maxSizeMb": 2048,
    "minFreeStorageMb": 500,
    "backend": "sqlite",
    "location": "/custom/cache/path"
  },
  "memory": {
    "defaultRamBudgetMb": 1024,
    "maxRamBudgetMb": 4096,
    "autoTune": true
  },
  "network": {
    "connectTimeoutMs": 10000,
    "readTimeoutMs": 30000,
    "maxRetries": 3,
    "proxy": {
      "host": "proxy.example.com",
      "port": 8080,
      "username": null,
      "password": null
    }
  },
  "behavior": {
    "logLevel": "INFO",
    "logFile": "~/.hbe/logs/hbe.log",
    "daemonEnabled": false,
    "parallelPhases": false,
    "javacUseApi": true
  },
  "plugins": {
    "path": "~/.hbe/plugins",
    "enabled": ["compose", "ksp"]
  }
}
```

### 26.2 Schema: `~/.hbe/config.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "HBE Engine Configuration",
  "type": "object",
  "properties": {
    "sdk": {
      "type": "object",
      "properties": {
        "path": { "type": "string" },
        "autoDownload": { "type": "boolean", "default": true },
        "preferredVersion": {
          "type": "object",
          "properties": {
            "buildTools": { "type": "string" },
            "platform": { "type": "integer" }
          }
        }
      }
    },
    "cache": {
      "type": "object",
      "properties": {
        "maxSizeMb": { "type": "integer", "minimum": 128, "default": 2048 },
        "minFreeStorageMb": { "type": "integer", "minimum": 100, "default": 500 },
        "backend": { "type": "string", "enum": ["sqlite", "fs"], "default": "sqlite" },
        "location": { "type": "string" }
      }
    },
    "memory": {
      "type": "object",
      "properties": {
        "defaultRamBudgetMb": { "type": "integer", "minimum": 256, "default": 1024 },
        "maxRamBudgetMb": { "type": "integer", "minimum": 256, "default": 4096 },
        "autoTune": { "type": "boolean", "default": true }
      }
    },
    "network": {
      "type": "object",
      "properties": {
        "connectTimeoutMs": { "type": "integer", "default": 10000 },
        "readTimeoutMs": { "type": "integer", "default": 30000 },
        "maxRetries": { "type": "integer", "default": 3 },
        "proxy": {
          "type": "object",
          "properties": {
            "host": { "type": "string" },
            "port": { "type": "integer" },
            "username": { "type": ["string", "null"] },
            "password": { "type": ["string", "null"] }
          }
        }
      }
    },
    "behavior": {
      "type": "object",
      "properties": {
        "logLevel": { "type": "string", "enum": ["DEBUG", "INFO", "WARN", "ERROR"], "default": "INFO" },
        "logFile": { "type": "string" },
        "daemonEnabled": { "type": "boolean", "default": false },
        "parallelPhases": { "type": "boolean", "default": false },
        "javacUseApi": { "type": "boolean", "default": true }
      }
    },
    "plugins": {
      "type": "object",
      "properties": {
        "path": { "type": "string", "default": "~/.hbe/plugins" },
        "enabled": { "type": "array", "items": { "type": "string" } }
      }
    }
  }
}
```

### 26.3 Configuration Precedence

1. Command-line arguments (highest)
2. `BuildRequest` fields (API mode)
3. `hbe.json` (project config)
4. `~/.hbe/config.json` (user config)
5. Built-in defaults (lowest)

---

## 27. Plugin SDK Specification

### 27.1 Purpose

Allow third-party developers to extend HBE with custom phases, tool overrides, and build logic. Plugins are JAR files loaded via ServiceLoader.

### 27.2 Plugin Interface

```java
public interface HbePlugin {
    /** Unique plugin identifier. */
    String getId();
    
    /** Human-readable plugin name. */
    String getName();
    
    /** Plugin version. */
    String getVersion();
    
    /** Called during HBE startup. Register phases, hooks, etc. */
    void onLoad(PluginContext context);
    
    /** Called before a phase executes. Can modify phase configuration. */
    default void onPhaseStart(PhaseContext ctx, Phase phase);
    
    /** Called after a phase completes. Can inspect/modify results. */
    default void onPhaseEnd(PhaseContext ctx, Phase phase, PhaseResult result);
    
    /** Called during build cleanup. */
    default void onUnload(PluginContext context);
}
```

### 27.3 Plugin Context

```java
public class PluginContext {
    /** Register a custom phase implementation. */
    void registerPhase(Class<? extends Phase> phaseClass);
    
    /** Register a tool override (replaces default tool path). */
    void registerTool(String toolName, Path toolPath);
    
    /** Register a dependency resolver extension. */
    void registerDependencyResolver(DependencyResolver resolver);
    
    /** Access engine configuration. */
    Config getConfig();
    
    /** Access logger. */
    HbeLogger getLogger();
    
    /** Access file system. */
    FileSystem getFileSystem();
    
    /** Register a build lifecycle listener. */
    void addBuildListener(BuildListener listener);
}
```

### 27.4 Plugin Packaging

```
my-plugin.jar
├── META-INF/
│   ├── MANIFEST.MF
│   └── services/
│       └── com.hbe.api.HbePlugin     # ServiceLoader file
│           content: "com.example.MyPlugin"
└── com/
    └── example/
        └── MyPlugin.class
```

### 27.5 Built-in Plugins

| Plugin | Phase | Responsibility |
|--------|-------|----------------|
| `compose` | SourceCompile (hook) | Adds Compose compiler plugin flags to kotlinc |
| `ksp` | SourceCompile | Manages KSP annotation processor classpath |
| `ndk` | NativeCompile | Invokes cmake or ndk-build for native code |
| `databinding` | SourceCompile (hook) | Adds databinding annotation processor |
| `lint` | PostPackage | Runs Android lint on compiled APK |

### 27.6 Plugin Lifecycle

```
HBE startup → discover plugins (ServiceLoader) 
             → onLoad(pluginContext) for each plugin
             → register phases/tools/hooks
Build start  → onPhaseStart for each phase
             → Phase execution
             → onPhaseEnd for each phase
Build end    → ...
HBE shutdown → onUnload for each plugin
```

### 27.7 Plugin Security

- Plugins run in the same JVM as HBE (full access)
- Plugin paths are configurable: only load from `~/.hbe/plugins` (not from project directory)
- Plugins cannot override core HBE phases (SDK resolve, dep resolve, signing) without explicit user opt-in
- Plugin manifest can declare `security: restricted` to limit file/network access (future)

---

## 28. AI Integration Protocol

### 28.1 Purpose

Define how AI systems (LLMs, agents, automation tools) interact with HBE. The protocol is designed for simplicity: a single JSON command in, a single JSON result out.

### 28.2 Protocol: Request

```json
{
  "jsonrpc": "2.0",
  "id": "req-001",
  "method": "build",
  "params": {
    "projectDir": "/path/to/project",
    "variant": "debug",
    "incremental": true,
    "ramBudgetMb": 2048
  }
}
```

### 28.3 Protocol: Response

```json
{
  "jsonrpc": "2.0",
  "id": "req-001",
  "result": {
    "status": "SUCCESS",
    "apkPath": "/path/to/output.apk",
    "buildId": "bld-abc123",
    "totalDurationMs": 45200,
    "apkSizeBytes": 4523124,
    "summary": "Build completed in 45s. 3 cache hits. APK: 4.3MB."
  }
}
```

### 28.4 Protocol: Error Response

```json
{
  "jsonrpc": "2.0",
  "id": "req-001",
  "error": {
    "code": -32000,
    "message": "Build failed",
    "data": {
      "phase": "SOURCE_COMPILE",
      "errorCode": "KOTLINC_COMPILE_ERROR",
      "details": [
        {
          "file": "src/main/java/com/example/MainActivity.kt",
          "line": 42,
          "column": 5,
          "message": "Unresolved reference: missingImport"
        }
      ],
      "suggestion": "Add import for com.example.missingImport or add the dependency to hbe.json"
    }
  }
}
```

### 28.5 Supported Methods

| Method | Description |
|--------|-------------|
| `build` | Execute build |
| `clean` | Clean project |
| `doctor` | Run diagnostics |
| `install` | Install APK via ADB |
| `downloadSdk` | Download SDK components |
| `resolveDependencies` | Pre-resolve dependencies |
| `analyze` | Analyze project structure |
| `cache` | Cache operations (stats, prune) |
| `cancel` | Cancel running build |
| `status` | Query build status / daemon health |
| `shutdown` | Stop HBE daemon |

### 28.6 JSON-RPC Transport

**Mode 1: Subprocess (one-shot)**

```
$ hbe --json < build-request.json
{ jsonrpc response }
```

**Mode 2: Daemon (persistent)**

```
$ hbe daemon start
$ hbe --rpc '{"method":"build","params":{...}}'
{ response }
```

**Mode 3: Streaming**

For long-running builds, the daemon sends progress notifications:

```json
{
  "jsonrpc": "2.0",
  "method": "progress",
  "params": {
    "phase": "SOURCE_COMPILE",
    "progress": 0.45,
    "estimatedRemainingMs": 30000
  }
}
```

### 28.7 AI-Friendly Features

- **Summary field**: A one-line human-readable summary of the result
- **Suggestion field**: When a build fails, HBE suggests a fix
- **Structured errors**: Machine-parseable error details (file, line, column, message)
- **Phase timing**: Per-phase breakdown for performance analysis
- **Cache stats**: Hit/miss rates for cache optimization
- **Build ID**: Unique identifier for cross-referencing logs and cache

---

## 29. JSON-RPC Protocol

### 29.1 Specification

HBE implements JSON-RPC 2.0 over stdin/stdout (subprocess mode) or TCP socket (daemon mode).

### 29.2 Request Format

```json
{
  "jsonrpc": "2.0",
  "id": "string-or-number",
  "method": "string",
  "params": {}  // optional, object or array
}
```

### 29.3 Response Format

```json
{
  "jsonrpc": "2.0",
  "id": "string-or-number",
  "result": {}  // present on success
}
```

```json
{
  "jsonrpc": "2.0",
  "id": "string-or-number",
  "error": {
    "code": -32000,
    "message": "string",
    "data": {}  // optional
  }
}
```

### 29.4 Error Codes

| Code | Meaning |
|------|---------|
| -32700 | Parse error (invalid JSON) |
| -32600 | Invalid request |
| -32601 | Method not found |
| -32602 | Invalid params |
| -32603 | Internal error |
| -32000 | Build error (see error.data.errorCode) |
| -32001 | SDK error |
| -32002 | Dependency resolution error |
| -32003 | Compilation error |
| -32004 | Cache error |
| -32005 | Network error |

### 29.5 Notification (No Response)

```json
{
  "jsonrpc": "2.0",
  "method": "cancel"
}
```

### 29.6 Daemon Transport: TCP Socket

- Default port: 8574 (HBE on phone keypad)
- Localhost only (`127.0.0.1`)
- JSON lines (newline-delimited JSON)
- Keepalive: send `{"jsonrpc":"2.0","method":"ping"}` every 30s
- Connection timeout: 5 minutes idle

### 29.7 Daemon Transport: Unix Socket

- Path: `/tmp/hbe-daemon.sock`
- Preferred on Android (no port conflicts)
- Same JSON-lines protocol


---

## 30. CLI Specification

### 30.1 Purpose

Command-line interface for direct human or script usage.

### 30.2 Usage

```
hbe <command> [options] [project-path]

Commands:
  build       Build APK (default command)
  clean       Clean build artifacts
  doctor      Run system diagnostics
  install     Install APK on device/emulator
  prepare     Pre-download SDK + dependencies (warmup)
  analyze     Analyze project structure
  cache       Manage build cache
  daemon      Start/stop/status daemon
  version     Print version
  help        Print help
```

### 30.3 Build Command

```
hbe build [options] [path]

Options:
  -v, --variant <name>      Build variant (debug/release) [default: debug]
  -c, --clean               Clean before building
  --no-incremental          Disable incremental build
  --min-sdk <api>           Override minSdkVersion
  --target-sdk <api>        Override targetSdkVersion
  --compile-sdk <api>       Override compileSdkVersion
  --compose                 Enable Compose compiler plugin
  --signing <type>          Signing type (debug/release/none) [default: debug]
  --keystore <path>         Release keystore path
  --keystore-pass <pass>    Keystore password
  --key-alias <name>        Key alias
  --key-pass <pass>         Key password
  --output <path>           Output APK path
  --ram-budget <mb>         Max RAM in MB [default: 1024]
  --daemon                  Use daemon (faster subsequent builds)
  --json                    Output as JSON
  -q, --quiet               Minimal output (errors only)
  -v, --verbose             Detailed output

Examples:
  hbe build
  hbe build ./my-project -v debug
  hbe build --clean --compose --output ./app.apk
  hbe build --json | jq '.apkPath'
```

### 30.4 Other Commands

```
hbe doctor [options]
  --json        Output diagnostics as JSON

hbe clean [path]
  --all         Clean all projects' artifacts

hbe install [path] [options]
  -d, --device <id>    Specific device serial

hbe prepare [path]
  --no-deps    Skip dependency resolution
  --no-sdk     Skip SDK download

hbe analyze [path]
  --json       Output as JSON

hbe cache [command]
  stats        Print cache statistics
  prune        Remove old cache entries
  clear        Clear entire cache

hbe daemon [command]
  start        Start daemon process
  stop         Stop daemon process
  status       Check daemon status
  restart      Restart daemon
```

### 30.5 Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Build/command failure |
| 2 | Invalid arguments |
| 3 | Configuration error |
| 4 | Network error |
| 5 | OOM / system resource error |
| 130 | Interrupted (Ctrl+C) |

### 30.6 Environment Variables

| Variable | Description |
|----------|-------------|
| `HBE_HOME` | Override `~/.hbe` directory |
| `HBE_SDK_HOME` | Override Android SDK path |
| `HBE_CACHE_HOME` | Override cache directory |
| `HBE_LOG_LEVEL` | Override log level |
| `HBE_RAM_BUDGET` | Override RAM budget (MB) |
| `HBE_NO_COLOR` | Disable colored output |
| `JAVA_HOME` | Java home (used if set) |
| `ANDROID_HOME` | Android SDK (used if set) |
| `ANDROID_SDK_ROOT` | Android SDK (used if set) |

### 30.7 Output Format (Human)

```
$ hbe build
[HBE] Building MyApp (debug)...
[HBE] ✓ SDK resolved (API 34, build-tools 34.0.0)
[HBE] ✓ Dependencies resolved (45 artifacts)
[HBE] ✓ Resources compiled (127 files)
[HBE] ✓ Sources compiled (32 Java, 28 Kotlin)
[HBE] ✓ DEX generated (classes.dex, 12450 methods)
[HBE] ✓ APK packaged (4.52 MB)
[HBE] ✓ APK signed (v1+v2+v3)

Build complete: /home/user/MyApp/build/app-debug.apk (4.52 MB)
Duration: 45.2s | Cache hits: 3 | RAM peak: 512 MB
```

---

## 31. Daemon Architecture

### 31.1 Purpose

A persistent background process that maintains JIT caches, warm compiler processes, and SDK/dependency state across multiple build invocations. The daemon reduces build time by 30-50% for incremental builds.

### 31.2 Architecture

```
┌─────────────────────────────────────────────┐
│           HBE Daemon Process                │
│                                             │
│  ┌──────────────┐  ┌──────────────────────┐ │
│  │ JSON-RPC      │  │ Build Executor       │ │
│  │ Server        │  │ (sequential, one at  │ │
│  │ (TCP/Unix     │  │  a time)             │ │
│  │  Socket)      │  └──────────────────────┘ │
│  └──────────────┘                            │
│  ┌──────────────┐  ┌──────────────────────┐ │
│  │ WarmCache    │  │ Kotlin Compiler      │ │
│  │ (SDK paths,  │  │ Daemon (kotlin-daemon)│ │
│  │  dep graph)  │  └──────────────────────┘ │
│  └──────────────┘                            │
│  ┌──────────────┐  ┌──────────────────────┐ │
│  │ Session Cache│  │ Process Pool         │ │
│  │ (in-memory)  │  │ (reusable JVMs)      │ │
│  └──────────────┘  └──────────────────────┘ │
└─────────────────────────────────────────────┘
```

### 31.3 Daemon Lifecycle

```
Start:
  1. Parse config
  2. Check if another daemon is running (pid file)
  3. Fork/spawn daemon process
  4. Initialize JSON-RPC server on socket
  5. Pre-warm SDK detection cache
  6. Pre-warm dependency graph for recent projects
  7. Wait for RPC connections

Per-request:
  1. Receive build request
  2. Quick cache check (warmCache)
  3. Execute build (same path as non-daemon)
  4. Return result
  5. Keep daemon alive for next request

Stop:
  1. Kill kotlin-daemon if running
  2. Flush session cache to disk
  3. Remove pid file
  4. Exit
```

### 31.4 Warm Cache (In-Memory)

```java
public class WarmCache {
    // SDK detection (rarely changes)
    SdkResolution sdkCache;              // cached for 1 hour
    
    // Recent dependency graphs
    LRUCache<String, DependencyGraph> depGraphCache;  // last 10 projects
    
    // Tool paths
    Map<String, Path> toolPaths;         // resolved tool binaries
    
    // Build configs
    LRUCache<String, Config> projectConfigs;  // last 20 projects
}
```

### 31.5 Kotlin Compiler Daemon

The Kotlin compiler daemon keeps the Kotlin compiler JVM warm between builds:

```
Without daemon:  kotlinc startup → 3-5s overhead per batch
With daemon:     kotlinc startup → 0.5-1s overhead per batch

Saving: ~2-4s per batch × 2-5 batches = 4-20s total
```

HBE's daemon starts `kotlin-daemon` at launch and sends compile requests via its client protocol. If `kotlin-daemon` crashes, HBE falls back to process-per-batch compilation.

### 31.6 Process Pool

```java
public class ProcessPool {
    // Pool of reusable JVM processes
    // (future optimization — warm JVMs that accept classpaths)
    
    int maxPoolSize = 2;
    List<WarmJvmProcess> pool;
    
    public WarmJvmProcess borrow(String classpath);
    public void release(WarmJvmProcess process);
}
```

### 31.7 Daemon PID File

```
~/.hbe/daemon.pid     # contains PID of running daemon
~/.hbe/daemon.sock    # Unix domain socket (preferred)
~/.hbe/daemon.log     # daemon-specific log
~/.hbe/daemon.json    # daemon state (port, start time, build count)
```

### 31.8 Auto-Shutdown

The daemon automatically shuts down after:
- 30 minutes of inactivity (configurable)
- Low memory condition (< 256MB free)
- `hbe daemon stop` command
- System shutdown (SIGTERM)

---

## 32. Logging Architecture

### 32.1 Purpose

Provide structured, machine-parseable logging with multiple output targets. Logs must support debugging, performance analysis, and AI consumption.

### 32.2 Log Levels

| Level | Usage |
|-------|-------|
| `DEBUG` | Detailed information for debugging builds |
| `INFO` | Normal build progress information (default) |
| `WARN` | Issues that don't fail the build |
| `ERROR` | Build errors and failures |
| `SILENT` | No output |

### 32.3 Log Format (Human)

```
[TIMESTAMP] [LEVEL] [PHASE] Message {key=value}
```

Example:
```
[2026-07-30T10:30:00.123Z] [INFO] [SDK] SDK resolved {compileSdk=34, path=/sdk}
[2026-07-30T10:30:01.456Z] [WARN] [DEP] Dependency androidx.core:core-ktx:1.12.0 already in cache
[2026-07-30T10:30:05.789Z] [ERROR] [COMPILE] Kotlin compilation failed {file=Main.kt, line=42}
```

### 32.4 Log Format (JSON)

```json
{
  "timestamp": "2026-07-30T10:30:00.123Z",
  "level": "INFO",
  "phase": "SDK",
  "message": "SDK resolved",
  "context": {
    "compileSdk": 34,
    "sdkPath": "/sdk"
  },
  "buildId": "bld-abc123"
}
```

### 32.5 Log Outputs

```java
public interface LogOutput {
    void write(LogEntry entry);
    void flush();
    void close();
}

// Standard output (colored)
public class ConsoleOutput implements LogOutput;

// JSON file (rotation)
public class JsonFileOutput implements LogOutput;

// Socket (for IDE integration)
public class SocketOutput implements LogOutput;

// Null (for tests)
public class NullOutput implements LogOutput;
```

### 32.6 Log Rotation

- File: `~/.hbe/logs/hbe-YYYY-MM-DD.log`
- Max size: 10MB per file
- Max files: 7 (last 7 days)
- Compression: gzip rotated files
- JSON logs: `~/.hbe/logs/hbe-YYYY-MM-DD.jsonl`

### 32.7 Performance

- Logging is **asynchronous** (background thread writes)
- JSON formatting is pre-computed and buffered
- In production, DEBUG-level logging adds <1% overhead
- Logs are never written during phase execution (only between phases) to avoid I/O contention

---

## 33. Diagnostics System

### 33.1 Purpose

Provide comprehensive health checks (`doctor`), build analytics, error analysis, and performance insights.

### 33.2 Doctor Command

```
Input: none
Output: HealthReport

Algorithm:

Function doctor():
    report = new HealthReport()
    
    // SDK check
    report.sdk = checkSdk()
    // Installed platforms, build-tools, NDK
    // Version mismatches, corrupted installations
    
    // JDK check
    report.jdk = checkJdk()
    // Version, location, compiler availability
    
    // Storage check
    report.storage = checkStorage()
    // Free space, cache size, temp space
    
    // Network check
    report.network = checkNetwork()
    // Connectivity to Maven repos, SDK repo
    
    // Performance check
    report.performance = checkPerformance()
    // CPU cores, RAM, Android vs Linux vs macOS
    
    // Cache health
    report.cache = checkCache()
    // Corrupted entries, hit rate, size
    
    // Permission check
    report.permissions = checkPermissions()
    // Write access to build dirs, ADB access
    
    return report
```

### 33.3 Health Report Format

```json
{
  "status": "WARNING",
  "checks": {
    "sdk": {
      "status": "OK",
      "message": "SDK API 34 found, build-tools 34.0.0 found"
    },
    "jdk": {
      "status": "OK",
      "message": "JDK 17.0.9 found at /usr/lib/jvm/java-17"
    },
    "storage": {
      "status": "WARNING",
      "message": "Free space: 1.2GB (below 2GB recommended)",
      "freeBytes": 1200000000,
      "recommendedBytes": 2000000000
    },
    "network": {
      "status": "OK",
      "message": "All repositories reachable"
    },
    "cache": {
      "status": "OK",
      "message": "Cache: 156 entries, 890MB, 87% hit rate"
    }
  },
  "recommendations": [
    "Free up at least 800MB of storage for large builds",
    "Consider enabling daemon for faster incremental builds"
  ]
}
```

### 33.4 Performance Analytics

After each build, HBE collects:

```
BuildAnalytics {
    buildId: String,
    projectName: String,
    variant: String,
    totalDurationMs: long,
    ramPeakMb: int,
    ramAverageMb: int,
    phaseBreakdown: Map<String, PhaseAnalytics>,
    cacheHitRate: double,
    batchCount: int,           // number of compiler batches
    totalFiles: int,
    totalLines: int,
    methodCount: int,
    dexCount: int,
    apkSize: long
}

PhaseAnalytics {
    durationMs: long,
    ramPeakMb: int,
    inputCount: int,
    cached: boolean,
    retryCount: int,
    errors: List<BuildError>
}
```

### 33.5 Error Analysis

```
Function suggestFix(error):
    switch error.code:
        case "KOTLINC_COMPILE_ERROR":
            return "Compile error in " + error.details[0].file 
                 + ":" + error.details[0].line + ". "
                 + "Fix the syntax error and rebuild."
        
        case "MAVEN_RESOLVE_FAILED":
            return "Dependency " + error.context.coordinate 
                 + " could not be resolved. "
                 + "Check the coordinate is correct and the repository is accessible."
        
        case "AAPT2_LINK_ERROR":
            return "Resource linking failed. "
                 + "Check for duplicate resource IDs or missing references."
        
        case "OUT_OF_MEMORY":
            return "Build ran out of memory. "
                 + "Try increasing RAM budget with --ram-budget or "
                 + "set ramBudgetMb in BuildRequest."
        
        case "SDK_NOT_FOUND":
            return "Android SDK not found. "
                 + "Run 'hbe doctor' to check SDK status or "
                 + "set ANDROID_HOME environment variable."
        
        default:
            return "Unknown error. Check logs for details."
```

### 33.6 Log Analysis

The diagnostics system can also analyze log files:

```
Function analyzeLogs(path):
    // Parse JSON log file
    // Identify warning patterns
    // Generate timeline of errors
    // Suggest optimizations (e.g., "cache hit rate low — consider increasing cache size")
```

---

## 34. Recovery System

### 34.1 Purpose

Handle build failures gracefully. Resume from the last successful checkpoint on crash. Detect and recover from cache corruption. Provide clear error messages and suggested fixes.

### 34.2 Checkpoint System

```java
public class RecoverySystem {
    
    /** Save checkpoint after successful phase execution. */
    public void checkpoint(PhaseState state) {
        // 1. Serialize PhaseState to JSON
        // 2. Write to ~/.hbe/checkpoints/<buildId>.json
        // 3. Include: completed phases, output hashes, config
    }
    
    /** Get last checkpoint for a project. */
    public Optional<PhaseState> getLastCheckpoint(String projectId) {
        // 1. List checkpoint files for project
        // 2. Sort by timestamp (descending)
        // 3. Parse and return most recent
    }
    
    /** Clear checkpoints for this project. */
    public void clearCheckpoints(String projectId) {
        // Remove checkpoint files
    }
    
    /** Check if recovery is possible. */
    public boolean isRecoveryAvailable(String projectId) {
        return getLastCheckpoint(projectId).isPresent()
            && !isCheckpointExpired(getLastCheckpoint(projectId));  // 24h TTL
    }
}
```

### 34.3 Recovery Flow

```
Build starts → Check for checkpoint
    ↓
Checkpoint found? → yes → Resume from last checkpoint
    ↓ no
Start from beginning (clean build)
    ↓
Phase executes → success → Save checkpoint
    ↓
Phase fails → RETRY ONCE
    ↓
Retry succeeds → Continue → Save checkpoint
    ↓
Retry fails → Report error, suggest fix, keep checkpoint for resume
```

### 34.4 Phase-Level Retry

```java
Function executeWithRetry(phase, maxRetries = 1):
    for attempt in 0..maxRetries:
        try:
            result = phase.execute(ctx, state)
            if result.status == SUCCESS:
                return result
        catch Exception e:
            if attempt < maxRetries:
                logger.warn("Phase failed, retrying", {phase: phase.getName(), attempt})
                // Clean up before retry
                deletePhaseOutput(phase)
                memoryManager.releaseMemory()
                continue
            throw e
```

### 34.5 Cache Corruption Detection

```
Function verifyCache(cacheManager):
    // Check random sample of cache entries
    sampleSize = min(100, cacheManager.totalEntries() * 0.01)
    sampleEntries = cacheManager.getRandomEntries(sampleSize)
    
    for entry in sampleEntries:
        storedHash = entry.contentHash
        actualHash = sha256(entry.artifactPath)
        
        if storedHash != actualHash:
            // Corruption detected
            cacheManager.invalidate(entry.key)
            logger.warn("Cache corruption fixed", {key: entry.key})
    
    // If corruption rate > 5%, suggest full cache clear
    if corruptionRate > 0.05:
        logger.warn("High cache corruption rate — consider 'hbe cache clear'")
```

### 34.6 Auto-Clean on Failure

If a build fails mid-phase, HBE:

1. Preserves the checkpoint from the last successful phase
2. Cleans the failed phase output
3. Reports the error with a suggestion
4. Does NOT clean successful phase outputs (they may be cacheable)
5. On next build, resumes from the checkpoint

### 34.7 Emergency Recovery

If HBE encounters a fatal error (JVM crash, disk full, corrupted state):

1. Save emergency checkpoint to `~/.hbe/recovery/`
2. Log stack trace and system state
3. Exit with error code
4. On restart, offer to resume from emergency checkpoint

---

## 35. Security Architecture

### 35.1 Purpose

Protect against supply chain attacks, unauthorized access, data exfiltration, and build system abuse.

### 35.2 Threat Model

| Threat | Source | Impact | Mitigation |
|--------|--------|--------|------------|
| Malicious Maven dependency | Remote | Code execution via compiler | Checksum verification; GPG signing (optional) |
| SDK binary tampering | Local or MiTM | Code execution via aapt2/d8 | SHA-256 checksums against Google hashes |
| Keystore theft | Local attacker | Signed malware distribution | Encrypted keystore; biometric unlock |
| Plugin abuse | User-installed plugin | Arbitrary code execution | Plugin sandboxing (future); user confirmation |
| Build config injection | Untrusted project | Read arbitrary files during build | No build scripts; declarative config only |
| Cache poisoning | Network MiTM | Serve malicious cached artifacts | Content hash verification on every cache read |

### 35.3 Dependency Verification

```java
public class DependencyVerifier {
    
    public boolean verify(MavenCoordinate coord, Path artifactFile) {
        // 1. Compute SHA-256 of artifact
        String actualHash = sha256(artifactFile);
        
        // 2. Fetch expected hash from repository
        String expectedHash = fetchHashFromRepo(coord);
        // POM → .sha256 file from Maven repo
        
        // 3. Compare
        if (!actualHash.equals(expectedHash)) {
            logger.error("Checksum mismatch", {coord, expectedHash, actualHash});
            return false;
        }
        
        // 4. Optional: GPG signature verification
        if (config.verifyGpgSignatures) {
            return verifyGpg(coord, artifactFile);
        }
        
        return true;
    }
}
```

### 35.4 Keystore Security

```java
public class KeystoreManager {
    
    // Debug keystore: stored in ~/.hbe/debug.keystore
    // Protected by file permissions (chmod 600)
    
    // Release keystore: user-provided, not stored by HBE
    // Options:
    //   1. File path (user manages security)
    //   2. Environment variable (HBE_KEYSTORE_PASS)
    //   3. Interactive prompt (stdin, masked)
    //   4. Android Keystore API (future)
    
    public SigningConfig resolveSigningConfig(BuildRequest request) {
        if (request.signingConfig.type == "debug") {
            return getDebugSigningConfig();
        }
        
        if (request.signingConfig.type == "release") {
            // Prompt for password if not provided
            if (request.signingConfig.keystorePassword == null) {
                request.signingConfig.keystorePassword = readPassword("Keystore password:");
            }
            return request.signingConfig;
        }
        
        return null;  // unsigned
    }
}
```

### 35.5 Sandboxing (Future)

- Plugin code runs in a separate ClassLoader with restricted permissions
- Network access limited to configured repositories
- File system access limited to project directory + HBE directories
- No system property modification without permission

### 35.6 Build Integrity

- Each build produces a `buildId` (UUID) that correlates all logs, artifacts, and cache entries
- Build manifests include hashes of all inputs (sources, dependencies, tools, config)
- APK can be traced back to its exact build environment (reproducible builds goal)

---

## 36. Build Database Design

### 36.1 Purpose

SQLite database that stores build history, incremental build state, cache metadata, and analytics. This is separate from the cache artifact store (which stores files).

### 36.2 Database: `~/.hbe/hbe-builds.db`

```sql
-- Projects
CREATE TABLE projects (
    project_id TEXT PRIMARY KEY,          -- hash of project root path
    project_name TEXT NOT NULL,
    root_path TEXT NOT NULL,
    project_type TEXT NOT NULL,            -- "hbe", "gradle", "raw"
    created_at INTEGER NOT NULL,
    last_build_at INTEGER
);

-- Builds
CREATE TABLE builds (
    build_id TEXT PRIMARY KEY,            -- UUID
    project_id TEXT NOT NULL,
    variant TEXT NOT NULL,
    status TEXT NOT NULL,                  -- "success", "failure", "cancelled"
    started_at INTEGER NOT NULL,
    completed_at INTEGER,
    duration_ms INTEGER,
    ram_peak_mb INTEGER,
    cache_hits INTEGER DEFAULT 0,
    cache_misses INTEGER DEFAULT 0,
    total_files INTEGER DEFAULT 0,
    method_count INTEGER DEFAULT 0,
    apk_size_bytes INTEGER,
    apk_path TEXT,
    error_code TEXT,
    error_message TEXT,
    hbe_version TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(project_id)
);

CREATE INDEX idx_builds_project ON builds(project_id, started_at DESC);
CREATE INDEX idx_builds_status ON builds(status, started_at DESC);

-- Phase details per build
CREATE TABLE build_phases (
    build_id TEXT NOT NULL,
    phase_name TEXT NOT NULL,
    status TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    ram_peak_mb INTEGER,
    input_count INTEGER,
    cached INTEGER DEFAULT 0,
    error_code TEXT,
    error_message TEXT,
    PRIMARY KEY (build_id, phase_name),
    FOREIGN KEY (build_id) REFERENCES builds(build_id)
);

-- File change tracking (incremental builds)
CREATE TABLE file_changes (
    build_id TEXT NOT NULL,
    file_path TEXT NOT NULL,
    change_type TEXT NOT NULL,             -- "added", "modified", "deleted", "unchanged"
    sha256_hash TEXT NOT NULL,
    PRIMARY KEY (build_id, file_path),
    FOREIGN KEY (build_id) REFERENCES builds(build_id)
);

-- Dependency resolution history
CREATE TABLE resolved_dependencies (
    build_id TEXT NOT NULL,
    coordinate TEXT NOT NULL,
    resolved_version TEXT NOT NULL,
    file_path TEXT,
    sha256_hash TEXT,
    PRIMARY KEY (build_id, coordinate),
    FOREIGN KEY (build_id) REFERENCES builds(build_id)
);

-- Build metrics for analytics
CREATE TABLE build_metrics (
    build_id TEXT NOT NULL,
    metric_name TEXT NOT NULL,
    metric_value REAL NOT NULL,
    PRIMARY KEY (build_id, metric_name),
    FOREIGN KEY (build_id) REFERENCES builds(build_id)
    -- metric_name examples: "compile_time_per_file_ms", "dex_method_count", "resource_count"
);
```

### 36.3 Queries

```sql
-- Last build status for a project
SELECT status, duration_ms, apk_path 
FROM builds 
WHERE project_id = ? 
ORDER BY started_at DESC 
LIMIT 1;

-- Average build time (last 10 builds)
SELECT AVG(duration_ms) 
FROM (
    SELECT duration_ms FROM builds 
    WHERE project_id = ? AND status = 'success'
    ORDER BY started_at DESC 
    LIMIT 10
);

-- Most common errors for a project
SELECT error_code, COUNT(*) as count
FROM builds
WHERE project_id = ? AND status = 'failure'
GROUP BY error_code
ORDER BY count DESC;

-- Cache hit rate over time
SELECT 
    SUM(cache_hits) as total_hits,
    SUM(cache_misses) as total_misses,
    SUM(CAST(cache_hits AS REAL)) / NULLIF(SUM(CAST(cache_hits + cache_misses AS REAL)), 0) as hit_rate
FROM builds
WHERE project_id = ?;
```

### 36.4 Migration Strategy

Schema versioning via `PRAGMA user_version`:

```
hbe-builds.db PRAGMA user_version = 1
```

Migrations are applied on version mismatch:

```
Function migrateDb(db):
    version = db.pragma("user_version")
    
    if version < 1:
        // Initial schema creation
        createTables(db)
        db.pragma("user_version = 1")
    
    if version < 2:
        // Future migration example
        db.execute("ALTER TABLE builds ADD COLUMN build_system TEXT")
        db.pragma("user_version = 2")
```


---

## 37. Artifact Management

### 37.1 Purpose

Track, store, and retrieve build artifacts (intermediate and final). Artifacts include compiled classes, dex files, resource bundles, and the final APK.

### 37.2 Artifact Categories

| Category | Examples | Retention | Location |
|----------|----------|-----------|----------|
| **Build output** | APK, AAR | Until next build | `~/.hbe/build/<project>/<variant>/` |
| **Intermediate** | `.class`, `.dex`, `.flat` | Until cleaned | `~/.hbe/build/<project>/<variant>/intermediate/` |
| **Generated source** | `R.java`, `DataBinding` | Until cleaned | `~/.hbe/build/<project>/<variant>/gen/` |
| **Cached** | Compiled outputs | LRU eviction | `~/.hbe/cache/artifacts/` |
| **Extracted AAR** | `classes.jar`, resources | Per version | `~/.hbe/cache/aar/` |
| **Downloaded** | SDK, JDK, tools | Permanent | `~/.hbe/sdk/`, `~/.hbe/jdk/` |

### 37.3 Build Output Layout

```
~/.hbe/build/<project-hash>/
├── debug/
│   ├── app-debug.apk
│   ├── app-debug-unsigned.apk
│   ├── app-debug.aab                     (future: Android App Bundle)
│   ├── intermediate/
│   │   ├── dex/
│   │   │   ├── classes.dex
│   │   │   ├── classes2.dex
│   │   │   └── ...
│   │   ├── classes/
│   │   │   ├── com/example/.../*.class
│   │   │   └── ...
│   │   ├── resources/
│   │   │   └── *.flat
│   │   └── manifest/
│   │       └── AndroidManifest.xml
│   ├── gen/
│   │   └── com/example/.../R.java
│   └── logs/
│       └── build-<buildId>.jsonl
├── release/
│   └── ...
└── artifact-map.json    # maps phase to output paths
```

### 37.4 Artifact Map

```json
{
  "buildId": "bld-abc123",
  "projectId": "proj-xyz",
  "variant": "debug",
  "artifacts": {
    "SDK_RESOLVE": {
      "paths": ["sdk/android-34/android.jar"],
      "hashes": {"android.jar": "sha256:..."}
    },
    "DEP_RESOLVE": {
      "paths": ["cache/aar/androidx.appcompat/appcompat/1.6.1/"],
      "count": 45
    },
    "RES_LINK": {
      "paths": [
        "build/debug/intermediate/resources/resources.arsc",
        "build/debug/gen/com/example/R.java"
      ],
      "hashes": {"resources.arsc": "sha256:..."}
    },
    "SOURCE_COMPILE": {
      "paths": ["build/debug/intermediate/classes/"],
      "fileCount": 342,
      "hashes": {"com/example/MainActivity.class": "sha256:..."}
    },
    "DEX": {
      "paths": [
        "build/debug/intermediate/dex/classes.dex",
        "build/debug/intermediate/dex/classes2.dex"
      ],
      "methodCount": 12450,
      "hashes": {"classes.dex": "sha256:..."}
    },
    "PACKAGE": {
      "paths": ["build/debug/app-debug-unsigned.apk"],
      "sizeBytes": 4523124
    },
    "SIGN": {
      "paths": ["build/debug/app-debug.apk"],
      "sizeBytes": 4523124
    }
  }
}
```

### 37.5 Clean Strategy

```
hbe clean → Delete build output for current project
  → Remove ~/.hbe/build/<project-hash>/
  → Clear build state in SQLite

hbe clean --all → Delete all build outputs
  → Remove ~/.hbe/build/
  → Remove ~/.hbe/cache/artifacts/
  → Reset build state in SQLite

hbe cache clear → Delete only cached artifacts
  → Remove ~/.hbe/cache/artifacts/*
  → Truncate cache_entries table
  → Keep extracted AARs and SDK (not cached artifacts)
```

---

## 38. File System Abstraction

### 38.1 Purpose

Abstract file system operations to support different runtimes (Android, Linux, Windows, macOS) and enable testing with in-memory file systems.

### 38.2 Interface

```java
public interface FileSystem {
    
    // File operations
    Path readAllBytes(Path path) throws IOException;
    void writeBytes(Path path, byte[] data) throws IOException;
    boolean exists(Path path);
    void delete(Path path) throws IOException;
    void deleteRecursively(Path path) throws IOException;
    void copy(Path source, Path target) throws IOException;
    void move(Path source, Path target) throws IOException;
    long size(Path path) throws IOException;
    
    // Directory operations
    void createDirectories(Path path) throws IOException;
    List<Path> listFiles(Path dir, String glob) throws IOException;
    List<Path> walkFiles(Path dir, String glob) throws IOException;
    
    // Metadata
    FileMetadata metadata(Path path) throws IOException;
    long lastModified(Path path) throws IOException;
    
    // Temporary files
    Path createTempFile(String prefix, String suffix) throws IOException;
    Path createTempDirectory(String prefix) throws IOException;
    
    // Locking
    Closeable acquireLock(Path path) throws IOException;
    
    // Monitoring
    WatchService newWatchService() throws IOException;
}

public class FileMetadata {
    Path path;
    long size;
    long lastModified;
    boolean isDirectory;
    boolean isFile;
    boolean isSymbolicLink;
    String sha256;  // computed on demand
}
```

### 38.3 Implementations

| Implementation | Use Case |
|----------------|----------|
| `OsFileSystem` | Default. Uses `java.nio.file` APIs |
| `InMemoryFileSystem` | Testing. All operations in memory (jimfs or custom) |
| `AndroidContentFileSystem` | Android content:// URI support (future) |
| `SafFileSystem` | Android Storage Access Framework (future) |

### 38.4 Path Normalization

- All paths stored in **canonical form** (no `.`, `..`, symlinks)
- Android: paths use `/` always (no `\`)
- Windows: `/` normalized to `\` at OS boundary
- Home directory: `~` expanded to `System.getProperty("user.home")`

### 38.5 Temporary File Management

```java
public class TempFileManager implements Closeable {
    
    public Path createTempFile(String prefix, String suffix);
    public Path createTempDirectory(String prefix);
    
    // All temp files are registered; deleted on close
    public void close();
    
    // Force cleanup (if engine crashes without close)
    public static void cleanupStaleTempFiles();
}
```

Temp files are stored in `~/.hbe/tmp/` and cleaned on engine start.

---

## 39. Network Layer

### 39.1 Purpose

HTTP(S) client for downloading SDK components, Maven artifacts, and plugin updates. Supports caching, retry, proxy, and authentication.

### 39.2 Interface

```java
public interface HttpClient {
    
    HttpResponse get(String url) throws NetworkException;
    HttpResponse get(String url, RequestConfig config) throws NetworkException;
    
    // Streaming download (for large files)
    void download(String url, Path destination) throws NetworkException;
    void download(String url, Path destination, ProgressCallback callback) throws NetworkException;
    
    // Check if URL is reachable
    boolean ping(String url);
}

public class HttpResponse {
    int statusCode;
    Map<String, String> headers;
    byte[] body;
    String bodyAsString();  // UTF-8 decode
}

public class RequestConfig {
    int connectTimeoutMs;       // default: 10000
    int readTimeoutMs;          // default: 30000
    int maxRetries;             // default: 3
    ProxyConfig proxy;
    Map<String, String> headers;  // custom headers
    boolean followRedirects;       // default: true
}
```

### 39.3 Implementations

| Implementation | Use Case |
|----------------|----------|
| `JavaNetHttpClient` | Default. Uses `java.net.http.HttpClient` (Java 11+) |
| `OkHttpClient` | Fallback. If available on classpath |
| `UrlConnectionClient` | Android fallback (Java 8 compat) |

### 39.4 Download Resume

```
Function download(url, destination):
    if exists(destination) and size(destination) > 0:
        // Partial download exists → resume
        existingSize = size(destination)
        headers = {"Range": "bytes=" + existingSize + "-"}
        response = httpClient.get(url, headers)
        
        if response.statusCode == 206:  // Partial Content
            appendToFile(destination, response.body)
            return
        elif response.statusCode == 416:  // Range Not Satisfiable
            delete(destination)  // file changed, start over
    
    // Full download
    response = httpClient.get(url)
    writeBytes(destination, response.body)
```

### 39.5 Repository Authentication

```java
public class Repository {
    String url;
    String username;      // null if no auth
    String password;      // null if no auth
    AuthType authType;    // NONE, BASIC, BEARER
    
    public HttpClient createClient() {
        if (authType == BASIC) {
            String encoded = base64(username + ":" + password);
            return new HttpClient(headers: {"Authorization": "Basic " + encoded});
        }
        return new HttpClient();
    }
}
```

### 39.6 Connection Pool

- Max connections: 4 (configurable)
- Keepalive: 30s
- Per-host limit: 2 concurrent connections
- DNS cache: 60s

### 39.7 Network Sandbox

By default, HBE only connects to:
1. `dl.google.com` (SDK download)
2. `repo1.maven.org` (Maven Central)
3. `dl.google.com/dl/android/maven2/` (Google Maven)
4. Repositories listed in `dependencies.repositories` in `hbe.json`
5. `api.adoptium.net` (JDK download)

All other connections are blocked unless explicitly allowed in config.

---

## 40. Testing Architecture

### 40.1 Purpose

Define how HBE modules are tested: unit tests, integration tests, and end-to-end (E2E) build tests.

### 40.2 Test Pyramid

```
      ╱╲
     ╱ E2E ╲           Few (critical build paths)
    ╱────────╲
   ╱ Integration ╲     Some (module interactions)
  ╱────────────────╲
 ╱    Unit tests     ╲   Many (individual modules)
╱──────────────────────╲
```

### 40.3 Unit Testing

**Framework**: JUnit 5 + MockK (Kotlin mocking)

**Target**: Each module in isolation.

| Module | Test Focus |
|--------|------------|
| core | Phase scheduling, DAG validation, error propagation |
| sdk | SDK path detection, version parsing, download URL construction |
| deps | POM parsing, conflict resolution, AAR extraction |
| resources | AAPT2 command construction, resource file detection |
| compiler | JDK Compiler API invocation, classpath construction |
| dex | d8 argument construction, main-dex-list generation |
| packager | ZIP entry ordering, alignment, compression |
| signer | Debug keystore generation, apksigner invocation |
| cache | Key construction, SQLite operations, eviction logic |
| mem | RAM budget calculation, batch size computation |
| plugin | Plugin loading, phase interception |
| recovery | Checkpoint serialization, resume logic |
| api | BuildRequest/BuildResult serialization |

**Example test structure**:

```kotlin
class DependencyManagerTest {
    
    @Test
    fun `resolves simple dependency`() {
        val mgr = DependencyManager(mockHttpClient, mockCache)
        val coord = MavenCoordinate("androidx.appcompat", "appcompat", "1.6.1")
        
        val graph = mgr.resolve(setOf(coord), defaultRepos)
        
        assertThat(graph.roots).hasSize(1)
        assertThat(graph.roots[0].coordinate.version).isEqualTo("1.6.1")
    }
    
    @Test
    fun `prefers nearest version on conflict`() {
        // Set up two roots with different versions of same dependency
        val graph = mgr.resolve(setOf(root1, root2), defaultRepos)
        
        // First declared dependency's version should win
        assertThat(graph.roots[0].dependencies[0].target.version)
            .isEqualTo("1.0.0")
    }
}
```

### 40.4 Integration Testing

**Target**: Multiple modules together, using real (or local) SDK tools.

| Test | Modules | What it verifies |
|------|---------|------------------|
| `SdkResolveIntegrationTest` | sdk + core | SDK detection, aapt2 binary location |
| `ResourceCompileIntegrationTest` | resources + sdk + core | AAPT2 compile + link produces valid .arsc |
| `JavaCompileIntegrationTest` | compiler + sdk + core | javac compiles with Android classpath |
| `KotlinCompileIntegrationTest` | compiler + sdk + core | kotlinc compiles with compose plugin |
| `DexIntegrationTest` | dex + sdk + core | d8 produces valid dex |
| `PackageSignIntegrationTest` | packager + signer + sdk + core | APK is signed and installable |
| `IncrementalBuildIntegrationTest` | cache + core | Changed file triggers minimal rebuild |
| `LowRamIntegrationTest` | mem + core | Build completes under RAM limit |

### 40.5 End-to-End Testing

**Target**: Full build pipeline with real Android projects.

Three test projects:
1. **HelloWorld** — single Java file, no resources → verify basic pipeline
2. **ComposeApp** — Kotlin + Compose + resources → verify Compose pipeline
3. **MultiModuleApp** — app + 2 library modules → verify multi-module pipeline

Each test:
1. Clones or uses a pre-cached project
2. Runs `hbe build --variant debug --clean`
3. Verifies APK exists, is signed, is installable
4. Runs incremental build (touch one file)
5. Verifies incremental build is faster and produces same APK

### 40.6 Performance Testing

**Benchmark suite** (JMH or custom):

```
HbeBenchmark
├── SdkResolveBench
├── DependencyResolveBench
├── ResourceCompileBench
├── JavaCompileBench (varying file counts: 10, 50, 200, 1000)
├── KotlinCompileBench (varying file counts: 10, 30, 100)
├── DexBench (varying class counts: 100, 1000, 10000)
├── PackageBench
├── FullBuildBench (clean + incremental)
└── MemoryBench (peak usage under various budgets)
```

### 40.7 RAM-Limited Testing

```
// Run build with artificial RAM limit (Linux)
$ ulimit -v 2500000  # 2.5GB virtual memory limit
$ hbe build --ram-budget 1024

// Or via cgroup (Android/Linux)
$ cgexec -g memory:limited_group hbe build
```

Test expected output: Build completes without OOM.

---

## 41. Benchmark Strategy

### 41.1 Purpose

Measure build performance across devices, project sizes, and RAM configurations. Track regressions and improvements.

### 41.2 Benchmark Metrics

| Metric | Unit | Collection Point |
|--------|------|-----------------|
| Total build time | ms | Build start → result |
| Per-phase time | ms | Phase start → end |
| RAM peak | MB | Maximum RSS during build |
| RAM average | MB | Average RSS during build |
| Cache hit rate | % | Cache hits / (hits + misses) |
| APK size | bytes | Final APK file size |
| Method count | count | Sum of DEX method counts |
| Batch count | count | Number of compiler batches |
| CPU usage | % | Average CPU utilization |
| I/O wait time | ms | Time spent in file operations |
| Network time | ms | Time spent downloading |

### 41.3 Test Projects

| Project | Size | Files | Dependencies | Compose |
|---------|------|-------|-------------|---------|
| **Tiny** | 1 Java file | 1 file | 0 | No |
| **Small** | HelloWorld-ish | 10 files | 5 deps | No |
| **Medium** | Realistic app | 50 files | 45 deps | Yes |
| **Large** | Enterprise app | 500 files | 200 deps | Yes |
| **Huge** | Complex app | 2000 files | 500 deps | Yes |

### 41.4 Benchmark Device Targets

| Class | Device | RAM | Storage | CPU |
|-------|--------|-----|---------|-----|
| Low-end | Android phone (Moto G) | 4GB | 64GB eMMC | Cortex-A53 |
| Mid-range | Pixel 6a | 6GB | 128GB UFS | Cortex-A76 |
| High-end | Pixel 8 Pro | 12GB | 256GB UFS | Cortex-X3 |
| Desktop | Linux workstation | 32GB | NVMe SSD | x86_64 |

### 41.5 Benchmark Reports

```json
{
  "benchmark": "FullBuildBench",
  "device": "Pixel 6a",
  "project": "Medium",
  "ramBudgetMb": 2048,
  "results": {
    "cleanBuild": {
      "totalDurationMs": 185000,
      "ramPeakMb": 1024,
      "cacheHits": 0,
      "phaseTimes": {...}
    },
    "incrementalBuild_1fileChanged": {
      "totalDurationMs": 35000,
      "ramPeakMb": 512,
      "cacheHits": 12,
      "phaseTimes": {...}
    },
    "incrementalBuild_noChanges": {
      "totalDurationMs": 5000,
      "ramPeakMb": 128,
      "cacheHits": 15
    }
  }
}
```

### 41.6 Performance Regression Detection

- Benchmark run on every milestone merge
- Compare against baseline (previous milestone)
- If build time increased by >10%: investigate
- If RAM peak increased by >15%: investigate
- If cache hit rate decreased by >5%: investigate

---

## 42. Performance Optimization Strategy

### 42.1 Purpose

Systematic approach to improving build speed and reducing resource usage across all milestones.

### 42.2 Optimization Phases

```
Phase 1 (Milestones 1-8): Correctness first
  - No optimization until the pipeline produces valid APKs
  - Baseline measurements established

Phase 2 (Milestones 9-10): Cache + Incremental
  - Cache reduces rebuild time by 80%+
  - Low-RAM batching prevents OOM
  - Target: incremental build < 30s

Phase 3 (Milestone 17): Parallelism
  - Parallel phases on high-RAM devices
  - Target: clean build 2x faster on 8GB vs 4GB

Phase 4 (Post-v1): Deep optimization
  - Kotlin daemon integration
  - JDK Compiler API for Java (faster than javac process)
  - Predictive dex layout
  - Concurrent dependency resolution
  - Lazy class loading
```

### 42.3 Bottleneck Prioritization

```
Rank | Bottleneck | Impact | Effort | Milestone
1    | Kotlin compilation | 50% of build time | High | 6, 10
2    | DEX generation | 20% of build time | Medium | 7, 14
3    | AAPT2 linking | 10% of build time | Low | 4
4    | Maven resolution | 10% of build time (first build) | Medium | 3
5    | Process spawn overhead | 5% of build time | High | 17
6    | File I/O (cached builds) | 5% of build time | Low | 9
```

### 42.4 Known Optimization Techniques

| Technique | Benefit | When |
|-----------|---------|------|
| Kotlin daemon | -3s per batch | v1.1 |
| JDK Compiler API | -2s per batch (no JVM spawn) | v1.0 |
| Class dependency analysis | Minimal recompile set | v1.2 |
| Concurrent dep resolution | -50% resolve time | v1.1 |
| Predictive multi-dex | Avoid second d8 pass | v1.1 |
| Lazy AAR extraction | Extract only needed AARs | v1.0 |
| Stream ZIP packaging | No temp APK copy | v1.0 |
| Memory-mapped cache reads | Faster cache lookup | v1.0 |

---

## 43. Error Handling Strategy

### 43.1 Principles

1. **Never lose errors** — All errors are captured, structured, and returned
2. **Fail fast** — Detect configuration errors before starting build
3. **Recover gracefully** — Phase-level retry, checkpoint resume
4. **Suggest fixes** — Every error includes a human-readable suggestion
5. **Log everything** — Errors are logged with full context

### 43.2 Error Categories

| Category | Example | Handling |
|----------|---------|----------|
| **Configuration** | Invalid hbe.json, missing required field | Fail before build starts |
| **Environment** | SDK not found, disk full | Suggest fix (doctor) |
| **Network** | Repository unreachable, timeout | Retry with backoff; fail after 3 retries |
| **Compilation** | Java compile error, Kotlin type mismatch | Capture all errors; report with file/line |
| **Tool failure** | aapt2 crash, d8 OOM | Retry once; if still fails, report tool error |
| **Resource exhaustion** | OOM, disk full | Cancel build; suggest RAM/storage increase |
| **Internal** | Null pointer, unexpected exception | Catch all; report as INTERNAL_ERROR |

### 43.3 Error Propagation

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│ Phase    │────►│ Core     │────►│ API      │────► Consumer
│ (catch)  │     │ (wrap)   │     │ (format) │
└──────────┘     └──────────┘     └──────────┘
```

Each layer adds context:
- **Phase**: Wraps with phase name, input file paths, tool output
- **Core**: Adds build ID, project name, variant, phase index
- **API**: Formats as JSON-RPC error or CLI error message

### 43.4 Structured Error Enum

```kotlin
sealed class BuildException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    
    class SdkNotFoundException(val sdkVersion: Int) : 
        BuildException("SDK platform android-$sdkVersion not found")
    
    class DependencyResolutionException(val coordinate: String, val repo: String) : 
        BuildException("Failed to resolve $coordinate from $repo")
    
    class CompilationException(
        val phase: String,
        val errors: List<CompilerError>
    ) : BuildException("$phase compilation failed with ${errors.size} error(s)")
    
    class DexException(val toolOutput: String) : 
        BuildException("DEX generation failed")
    
    class OutOfMemoryException(val phase: String, val budgetMb: Int) : 
        BuildException("$phase ran out of memory (budget: ${budgetMb}MB)")
    
    class ProcessCrashedException(
        val tool: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) : BuildException("$tool crashed with exit code $exitCode")
}
```

### 43.5 Error Display

```json
{
  "error": {
    "code": "KOTLINC_COMPILE_ERROR",
    "message": "Kotlin compilation failed with 2 error(s)",
    "suggestion": "Fix the syntax errors in the listed files and rebuild.",
    "details": [
      {
        "file": "/project/src/main/kotlin/com/example/MainActivity.kt",
        "line": 42,
        "column": 9,
        "message": "Unresolved reference: missingFunction",
        "toolOutput": "e: /project/src/main/kotlin/com/example/MainActivity.kt: (42, 9): Unresolved reference: missingFunction"
      }
    ]
  }
}
```

---

## 44. Versioning Strategy

### 44.1 HBE Version

HBE follows **Semantic Versioning** (MAJOR.MINOR.PATCH):

- **MAJOR**: Breaking API changes, pipeline architecture changes
- **MINOR**: New features, non-breaking API additions
- **PATCH**: Bug fixes, performance improvements, no API changes

### 44.2 Version File

```
~/.hbe/version
Content: "1.0.0"
```

### 44.3 Compatibility Matrix

```
HBE Version | Builds with SDK | Supported Java | Supported Kotlin
1.0.x       | 30-35           | 11, 17         | 1.8.x - 2.0.x
1.1.x       | 30-36           | 11, 17, 21     | 1.8.x - 2.1.x
```

Breaking changes require a MAJOR version bump and are documented in `MIGRATION.md`.

### 44.4 Build Metadata

Each build records the HBE version in the build database:

```
hbe_version: "1.0.0+build.20260730"
```

This enables tracing build issues to specific engine versions.

### 44.5 Cache Version

Cache entries include a `cache_version` field:

```
cache_version = 1  // bumped when cache format changes
```

If a build encounters cache entries with a different `cache_version`, the entire cache is invalidated.

---

## 45. Migration Strategy from Gradle Projects

### 45.1 Purpose

Provide a smooth migration path for existing Gradle-based Android projects to HBE. Migrating should be as simple as running `hbe init` in the project directory.

### 45.2 `hbe init` Command

```
$ hbe init [project-path]
```

```
1. Detect project type (Gradle, AndroidIDE, raw)
2. Parse build configuration (see §23)
3. Generate hbe.json with extracted configuration
4. Generate .hbeignore (equivalent to .gitignore for build outputs)
5. Suggest any needed manual edits
6. Ask: "Run first build?" [Y/n]
```

### 45.3 What `hbe init` Extracts

| From Gradle | To hbe.json |
|-------------|-------------|
| `compileSdk` | `android.compileSdk` |
| `minSdk` / `targetSdk` | `android.minSdk` / `targetSdk` |
| `implementation` dependencies | `dependencies.implementation` |
| `api` dependencies | `dependencies.api` |
| `compileOnly` dependencies | `dependencies.compileOnly` |
| `annotationProcessor` | `dependencies.annotationProcessor` |
| `compose = true` | `options.compose = true` |
| `viewBinding = true` | `options.viewBinding = true` |
| `buildTypes.debug` | `buildTypes.debug` |
| `buildTypes.release.minifyEnabled` | `buildTypes.release.minify` |
| `buildTypes.release.proguardFiles` | `buildTypes.release.proguardRules` |
| `defaultConfig.applicationId` | Inferred from manifest |
| `ndkVersion` | `android.ndkVersion` |
| `sourceSets.main.java.srcDirs` | `sourceSets.main.java` |
| `sourceSets.main.res.srcDirs` | `sourceSets.main.res` |

### 45.4 Manual Migration Required For

- Custom Gradle tasks
- BuildSrc convention plugins
- Version catalogs (`libs.versions.toml`)
- Flavor dimensions / product flavors (v2 feature)
- Dynamic feature modules (v2 feature)
- Custom `aaptOptions`
- Test instrumentation configuration
- Lint configuration

### 45.5 Coexistence with Gradle

HBE does **not** modify or remove `build.gradle` files. Projects can keep their Gradle configuration alongside HBE configuration. `hbe build` uses `hbe.json`; `./gradlew assembleDebug` continues to work unchanged.

### 45.6 Migration Guide (Future Document)

The migration guide will include:
1. Running `hbe init` in existing projects
2. Comparing `hbe build` output with `./gradlew assembleDebug` output
3. Manual adjustment for unsupported features
4. Performance comparison
5. CI/CD migration

---

## 46. Future Roadmap (v1, v2, v3)

### 46.1 HBE v1: Foundation (Milestones 0-14)

**Goal**: Build a valid signed APK from a single-module Android project.

| Feature | Included |
|---------|----------|
| Single-module APK build | ✅ |
| Java compilation | ✅ |
| Kotlin compilation | ✅ |
| Compose support | ✅ |
| Resource compilation | ✅ |
| DEX + multi-dex | ✅ |
| Debug signing | ✅ |
| Release signing | ✅ |
| Maven dependency resolution | ✅ |
| SDK auto-download | ✅ |
| Incremental builds | ✅ |
| Basic cache | ✅ |
| Low-RAM (4GB) | ✅ |
| CLI | ✅ |
| JSON-RPC API | ✅ |
| HBE project format | ✅ |
| Gradle project compatibility | ⚠️ Basic |
| Plugin system | ✅ Basic |
| Diagnostics | ✅ |
| Error recovery | ✅ |
| Build database | ✅ |

**v1 Excluded** (for v2):
- Multi-module projects
- NDK / native code
- Android App Bundle (AAB)
- Product flavors
- Remote cache
- Advanced diagnostics (lint integration)
- Kotlin daemon warmup
- View binding / data binding

### 46.2 HBE v2: Scale (Milestones 15-17 + extra)

**Goal**: Multi-module projects, NDK support, performance optimization.

| Feature | Target |
|---------|--------|
| Multi-module (library + app) | ✅ |
| NDK / CMake / ndk-build | ✅ |
| AAB (Android App Bundle) | ✅ |
| Product flavors | ✅ |
| Dynamic feature modules | ✅ |
| Parallel phase execution | ✅ |
| Kotlin daemon integration | ✅ |
| View binding | ✅ |
| Data binding | ✅ |
| Remote cache (HTTP/S3) | ✅ |
| More Gradle compatibility | ✅ |
| Performance benchmarks | ✅ |

### 46.3 HBE v3: Ecosystem

**Goal**: Distributed builds, IDE integration, CI/CD integration, advanced tooling.

| Feature | Target |
|--------|--------|
| Distributed builds | ✅ |
| IDE integration (Android Studio plugin) | ✅ |
| CI/CD integration (GitHub Actions, Jenkins) | ✅ |
| Grafana / Prometheus metrics | ✅ |
| Build analytics dashboard | ✅ |
| Predictive compilation (ML-based) | ✅ |
| Full Gradle DSL parsing | ✅ |
| Lint integration | ✅ |
| Android Testing support | ✅ |
| Resource shrinking (bundletool) | ✅ |
| Reproducible builds | ✅ |
| Kotlin Multiplatform | ⚠️ Research |

### 46.4 Feature Request Process

1. Open GitHub issue with use case
2. Evaluate against design principles:
   - Does it simplify the build process?
   - Does it reduce memory usage?
   - Does it increase compatibility?
   - Is it backward compatible?
3. If accepted → add to roadmap milestone
4. If rejected → document rationale in issue

### 46.5 Deprecation Policy

- Deprecated features are marked in release notes
- Deprecated features continue to work for 2 MINOR versions
- Removal happens on MAJOR version bump
- Migration path is documented before removal

---

## Appendices

### A. Glossary

| Term | Definition |
|------|------------|
| AAR | Android Archive — library format containing compiled code + resources |
| AAPT2 | Android Asset Packaging Tool 2 — compiles/link resources |
| ABD | Android Debug Bridge — communicates with Android devices |
| API | Application Programming Interface |
| APK | Android Package — application distribution format |
| D8 | Dex compiler — converts .class to .dex |
| DEX | Dalvik Executable — Android bytecode format |
| LRU | Least Recently Used — cache eviction policy |
| NDK | Native Development Kit — for C/C++ code |
| OOM | Out Of Memory |
| POM | Project Object Model — Maven dependency descriptor |
| R8 | ProGuard replacement — optimizer and obfuscator |
| RPC | Remote Procedure Call |
| SDK | Software Development Kit |
| SHA | Secure Hash Algorithm |

### B. References

- [Android Build Process](https://developer.android.com/studio/build)
- [AAPT2 Documentation](https://developer.android.com/studio/command-line/aapt2)
- [D8 / R8 Documentation](https://developer.android.com/studio/command-line/d8)
- [Maven Dependency Resolution](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html)
- [APK Signature Scheme v2/v3](https://source.android.com/docs/security/features/apksigning)
- [JSON-RPC 2.0 Specification](https://www.jsonrpc.org/specification)
- [Kotlin Compiler Plugins](https://kotlinlang.org/docs/compiler-plugins.html)

### C. Architecture Decision Records

| ADR | Decision | Date |
|-----|----------|------|
| 001 | Use Kotlin + Java interop for engine | 2026-07-30 |
| 002 | JSON-RPC over stdin/stdout for AI transport | 2026-07-30 |
| 003 | HBE JSON format (not build.gradle) | 2026-07-30 |
| 004 | SQLite-backed cache | 2026-07-30 |
| 005 | Process spawn for SDK tools (not in-process) | 2026-07-30 |
| 006 | Nearest-wins dependency resolution | 2026-07-30 |
| 007 | Phase-level parallelism only | 2026-07-30 |
| 008 | Content-hash-based cache keys | 2026-07-30 |
| 009 | Phase-level checkpoint recovery | 2026-07-30 |
| 010 | apksigner wrapper (not reimplementation) | 2026-07-30 |

