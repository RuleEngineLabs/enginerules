rootProject.name = "enginerules"

val modules = linkedMapOf(
    "engine-domain" to "engine-domain",
    "engine-application" to "engine-application",
    "adapter-expression-builtin" to "adapters/expression-builtin",
    "adapter-json-codec" to "adapters/json-codec",
    "adapter-persistence-documents" to "adapters/persistence-documents",
    "adapter-persistence-filesystem" to "adapters/persistence-filesystem",
    "adapter-persistence-aws" to "adapters/persistence-aws",
    "adapter-http-connector" to "adapters/http-connector",
    "adapter-grpc-connector" to "adapters/grpc-connector",
    "adapter-cache-memory" to "adapters/cache-memory",
    "adapter-web" to "adapters/web",
    "app-engine" to "apps/engine",
    "app-admin" to "apps/admin",
    "architecture-tests" to "architecture-tests",
    "integration-tests" to "integration-tests",
)

modules.forEach { (name, directory) ->
    include(name)
    project(":$name").projectDir = file(directory)
}
