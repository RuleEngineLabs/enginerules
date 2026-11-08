plugins {
    id("org.springframework.boot")
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":engine-application"))
    implementation(project(":adapter-expression-builtin"))
    implementation(project(":adapter-persistence-filesystem"))
    implementation(project(":adapter-persistence-aws"))
    implementation(project(":adapter-http-connector"))
    implementation(project(":adapter-grpc-connector"))
    implementation(project(":adapter-cache-memory"))
    implementation(project(":adapter-web"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("engine.jar")
}
