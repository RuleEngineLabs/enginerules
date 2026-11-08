# AGENTS.md

## Code style

No comments. Names must express intent; a comment that restates the name adds noise, not signal.
Only add a comment when a constraint, invariant, or workaround would surprise a future reader and cannot be named away.

Clean Code principles apply throughout:
- Single Responsibility: one reason to change per type or function.
- Meaningful names: reveal intent, avoid noise words and abbreviations.
- Small functions: if a function needs a mental map to follow, extract.
- No duplication: the same logic in two places is a future bug waiting to diverge.
- Fail fast: validate at system boundaries; trust internal guarantees.

## Architecture rules

- `engine-domain` depends only on the JDK. No Spring, no Jackson, no SDKs, no annotations from frameworks.
- `engine-application` depends only on `engine-domain`. One use case interface per business operation in `port.in`; output ports in `port.out`, sliced by need.
- Adapters depend on the core and never on each other (except `adapter-json-codec`).
- Spring lives only in `adapter-web` and `apps/*`. Wiring is explicit with `@Bean` in the apps.
- Business rules stay in aggregates (`Policy`) and domain services (`PolicyCompiler`, `PolicyExecutor`, `ChangeClassifier`); controllers only translate HTTP.
- `./gradlew :architecture-tests:test` must stay green.

## Tests

- Domain: unit tests with real value objects and the built-in expression language, fakes for I/O.
- Application: use cases exercised end to end with in-memory port implementations.
- Adapters: integration tests against the real resource (temp directories, local HTTP).
