# HBE Testing Guide

## Test Pyramid

```
      /\
     / E2E \           Few (critical build paths)
    /--------\
   / Integration \     Some (module interactions)
  /----------------\
 /    Unit tests    \  Many (individual modules)
/--------------------\
```

## Running Tests

```bash
# All tests
./gradlew test

# Single module
./gradlew :hbe-api:test
./gradlew :hbe-graph:test

# Single test class
./gradlew :hbe-api:test --tests "*MavenCoordinateTest*"

# With coverage
./gradlew test jacocoTestReport
```

## Unit Tests

**Framework:** JUnit 5 + MockK

**Location:** `src/test/kotlin/` in each module

**Target:** Test individual classes in isolation. Mock all dependencies.

**Example:**

```kotlin
class MavenCoordinateTest {
    @Test
    fun `parses standard three-part coordinate`() {
        val coord = MavenCoordinate.parse("group:artifact:1.0")
        assertEquals("group", coord.groupId)
        assertEquals("artifact", coord.artifactId)
        assertEquals("1.0", coord.version)
    }
}
```

## Integration Tests

**Location:** `hbe-tests/src/test/kotlin/`

**Target:** Test module interactions using real implementations.

**Example:**

```kotlin
class BuildPipelineIntegrationTest {
    @Test
    fun `BuildRequest round-trip`() {
        val request = BuildRequest(projectDir = "/test")
        assertEquals("debug", request.variant)
    }
}
```

## E2E Tests

**Location:** `hbe-tests/src/test/kotlin/e2e/`

**Target:** Full build pipeline with real test projects.

**Test projects:**
- `HelloWorld` — single Java file, no resources
- `ComposeApp` — Kotlin + Compose + resources
- `MultiModuleApp` — app + 2 library modules

## Test Fixtures

- `InMemoryFileSystem` — in-memory filesystem for testing
- `MockProcessRunner` — mock process runner that returns canned responses
- `MockNetworkClient` — mock HTTP client that returns local files

## Performance Tests

**Location:** `hbe-tests/src/jmh/` (future)

**Framework:** JMH

**Benchmarks:**
- Dependency resolution (varying graph sizes)
- Source compilation (varying file counts)
- Full build (clean + incremental)
- Memory usage under various budgets

## Coverage Targets

- Module coverage: >= 80% line coverage
- Core engine coverage: >= 90%
- Integration tests: all critical paths
- E2E tests: all supported project types
