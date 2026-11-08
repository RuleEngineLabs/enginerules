dependencies {
    api(project(":engine-domain"))
    implementation(project(":adapter-json-codec"))
    implementation(platform("io.grpc:grpc-bom:1.73.0"))
    implementation(platform("com.google.protobuf:protobuf-bom:4.31.1"))
    api("io.grpc:grpc-api")
    implementation("io.grpc:grpc-stub")
    runtimeOnly("io.grpc:grpc-netty-shaded")
    api("com.google.protobuf:protobuf-java")
    implementation("com.google.protobuf:protobuf-java-util")
    testImplementation(platform("io.grpc:grpc-bom:1.73.0"))
    testImplementation("io.grpc:grpc-inprocess")
}
