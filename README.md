# HMX Build Engine (HBE)

**Version:** 1.0.0-SNAPSHOT  
**Status:** Foundation Phase (Pre-Alpha)

HBE is a standalone, embeddable Android APK build engine designed to run on low-end hardware (4GB RAM target) without requiring Gradle, Android Studio, or any IDE.

## Architecture

HBE compiles Java/Kotlin source code, Android resources, and dependencies into a signed, aligned APK through a modular pipeline of isolated phases. Each phase runs in its own process, enabling clean memory release and OOM isolation.

Key design principles:
- **Modular** — 19 independent modules with defined interfaces
- **Process isolation** — Each tool (javac, kotlinc, d8, aapt2) runs in its own JVM
- **Batch compilation** — Source files are compiled in batches to cap memory usage
- **Content-addressed cache** — SHA-256 based caching for incremental builds
- **JSON-RPC API** — Language-agnostic interface for AI, CLI, and programmatic use

## Project Structure

```
hbe/
├── hbe-api/          # Public DTOs, interfaces, error hierarchy
├── hbe-core/         # Engine lifecycle, configuration, logging
├── hbe-infra/        # Filesystem, network, process runner abstractions
├── hbe-graph/        # Build graph DAG model and validation
├── hbe-cache/        # Content-addressed build cache
├── hbe-memory/       # RAM monitoring and batch size computation
├── hbe-scheduler/    # Task scheduling and execution planning
├── hbe-sdk/          # Android SDK detection and management
├── hbe-dependency/   # Maven dependency resolution
├── hbe-resources/    # AAPT2 resource compilation
├── hbe-compiler/     # Java/Kotlin source compilation
├── hbe-dex/          # DEX generation via d8/R8
├── hbe-packager/     # APK packaging and zipalign
├── hbe-signer/       # APK signing (v1/v2/v3)
├── hbe-diagnostics/  # Health checks and error analysis
├── hbe-recovery/     # Checkpoint-based crash recovery
├── hbe-plugins/      # Plugin loading via ServiceLoader
├── hbe-cli/          # Command-line interface
├── hbe-daemon/       # Persistent daemon with JSON-RPC server
└── hbe-tests/        # Integration tests
```

## Building

```bash
./gradlew build
```

## Running

```bash
# CLI mode
./gradlew :hbe-cli:run --args="build /path/to/project"

# Daemon mode
./gradlew :hbe-daemon:run --args="start"

# Help
./gradlew :hbe-cli:run --args="help"
```

## Documentation

- [Architecture Blueprint](ARCHITECTURE.md) — High-level design
- [Technical Specification](SPECIFICATION.md) — Detailed spec (46 sections)
- [Roadmap](ROADMAP.md) — Development milestones
- [Contributing](CONTRIBUTING.md) — How to contribute
- [Coding Standard](CODING_STANDARD.md) — Code style and conventions
- [Testing Guide](TESTING_GUIDE.md) — How to write and run tests
- [Architecture Decisions](DECISIONS.md) — Key technical decisions
- [Changelog](CHANGELOG.md) — Release history

## License

HMX Build Engine — Copyright 2026 HMX
