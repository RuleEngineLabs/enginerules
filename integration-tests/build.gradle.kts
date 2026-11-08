dependencies {
    testImplementation(project(":engine-domain"))
    testImplementation(project(":adapter-json-codec"))
    testImplementation(project(":adapter-persistence-aws"))
    testImplementation(project(":app-engine"))
    testImplementation(project(":app-admin"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation(platform("io.grpc:grpc-bom:1.73.0"))
    testImplementation(platform("com.google.protobuf:protobuf-bom:4.31.1"))
    testImplementation("io.grpc:grpc-api")
    testImplementation("io.grpc:grpc-stub")
    testImplementation("com.google.protobuf:protobuf-java")
    testRuntimeOnly("io.grpc:grpc-netty-shaded")
}

tasks.named<Test>("test") {
    enabled = false
}

val integrationTest by tasks.registering(Test::class) {
    description = "Boots admin and engine and runs every feature scenario over HTTP (LocalStack when ENGINERULES_IT_AWS_ENDPOINT is set)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/*IT.class")
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
