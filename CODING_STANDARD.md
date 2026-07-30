# HBE Coding Standard

## Language

- Primary: **Kotlin** for engine code
- Java allowed for JDK Compiler API interactions
- Kotlin version: 1.9.x
- JVM target: 17

## Code Style

- Follow Kotlin official coding conventions
- 4-space indentation (no tabs)
- Maximum line length: 120 characters
- Open braces on same line (K&R style)
- One blank line between methods
- Two blank lines between classes

## Naming

- **Classes/Interfaces:** PascalCase (`BuildRequest`, `FileSystem`)
- **Functions/Methods:** camelCase (`executeBuild`, `resolveSdk`)
- **Constants:** UPPER_SNAKE_CASE (`MAX_RETRY_COUNT`)
- **Packages:** lowercase with dots (`com.hbe.api.dto`)
- **Type parameters:** Single uppercase letter (`T`, `R`, `E`)

## Documentation

- Every public class/interface: KDoc with description
- Every public method: KDoc with description and param/return tags
- Every exception: Document when it's thrown
- Complex algorithms: Comment the "why" not the "what"

```kotlin
/**
 * Resolves Maven dependencies by traversing POM dependency trees.
 * Uses nearest-wins conflict resolution strategy.
 *
 * @param roots Root dependencies to resolve
 * @param repositories Maven repository URLs
 * @return Resolved dependency graph
 * @throws DependencyException if resolution fails
 */
fun resolve(roots: Set<MavenCoordinate>, repositories: List<String>): DependencyGraph
```

## Module Boundaries

- No circular dependencies between modules
- `api` module has zero dependencies
- `core` depends on `api`, `graph`, `scheduler`, `infra`
- `infra` depends on `api`
- All other modules depend on `api` and `infra`
- Cross-module communication only through interfaces

## Error Handling

- Use the typed exception hierarchy (see `com.hbe.api.exception`)
- Never catch and ignore exceptions
- Always provide a suggestion with BuildException
- Recoverable vs non-recoverable distinction

## Testing

- JUnit 5 for all tests
- MockK for mocking in Kotlin tests
- InMemoryFileSystem for filesystem tests
- Test class name: `<ClassUnderTest>Test`
- One test class per production class
- Test method name: backtick-wrapped descriptive names

## Imports

- No wildcard imports
- Organize: Kotlin stdlib → third-party → HBE modules
- Remove unused imports before committing
