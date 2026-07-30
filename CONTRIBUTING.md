# Contributing to HBE Build Engine

## Getting Started

1. Fork the repository
2. Clone your fork
3. Run `./gradlew build` to verify the project compiles
4. Read the [Architecture Blueprint](ARCHITECTURE.md) and [Technical Specification](SPECIFICATION.md)

## Development Workflow

1. Pick a milestone from [ROADMAP.md](ROADMAP.md)
2. Create a branch: `git checkout -b milestone-<name>`
3. Implement changes
4. Ensure compilation: `./gradlew build`
5. Run tests: `./gradlew test`
6. Format code: `./gradlew ktlintFormat` (once configured)
7. Submit a pull request

## Code Standards

See [CODING_STANDARD.md](CODING_STANDARD.md) for detailed code conventions.

## Module Guidelines

- Every public class must have KDoc documentation
- Every interface must define its responsibility in KDoc
- Module boundaries are strict: no cross-module direct dependencies (use interfaces)
- Follow SOLID principles
- Keep modules loosely coupled — constructor injection only (no DI framework)

## Testing

- Unit tests for every public method
- Integration tests for module interactions
- E2E tests for full build pipeline
- See [TESTING_GUIDE.md](TESTING_GUIDE.md) for details

## Pull Request Process

1. Update CHANGELOG.md with your changes
2. Ensure all tests pass
3. Ensure documentation is updated
4. Request review from maintainers
