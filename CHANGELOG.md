# Changelog

## [1.0.0-SNAPSHOT] — Foundation Phase

### Added
- Project scaffold with Gradle multi-module build system
- 19 module structure with package layout
- Error hierarchy: `BuildException` with 9 subclasses
- API DTOs: `BuildRequest`, `BuildResult`, `PhaseTiming`, `BuildError`, `SigningConfig`, `MavenCoordinate`, `ArtifactKey`
- Core interfaces: `Phase`, `PhaseContext`, `BuildContext`, `CancellationToken`
- Infrastructure abstractions: `FileSystem`, `ProcessRunner`, `NetworkClient`
- Configuration system: `EngineConfig`, `ConfigLoader`
- Logging system: `Logger` interface + `DefaultLogger` implementation
- Build graph: DAG model, topological sort, cycle detection, validation
- Cache interfaces: `CacheManager`, `CacheStats`, `CacheResult`
- Memory management: `MemoryMonitor` interface + `MemoryManagerImpl`
- Task scheduler: `TaskScheduler`, `ExecutionPlan`, `ExecutionBatch`
- Module manager interfaces: `SdkManager`, `DependencyManager`, `ResourceCompiler`, `SourceCompiler`, `DexEngine`, `Packager`, `Signer`
- Diagnostics: `Diagnostics`, `HealthReport`, `DiagnosticReport`
- Recovery system: `RecoverySystem` with checkpoint/restore
- Plugin system: `HbePlugin`, `PluginContext`, `PluginLoader`
- CLI entry point: `HbeCli` with 10 commands
- Daemon entry point: `HbeDaemon` with JSON-RPC server
- Partial implementations: `OsFileSystem`, `OsProcessRunner`, `JavaNetHttpClient`, `SdkManagerImpl`, `SignerImpl`, `PackagerImpl`, `DexEngineImpl`, `SourceCompilerImpl`, `ResourceCompilerImpl`
- Test infrastructure: `InMemoryFileSystem`, unit tests for API, graph, core
- Documentation: README, ROADMAP, CONTRIBUTING, CODING_STANDARD, CHANGELOG, DECISIONS, TESTING_GUIDE
- Architecture Blueprint and Technical Specification

### Changed
- Initial foundation setup (no prior version)

### Fixed
- N/A (initial release)
