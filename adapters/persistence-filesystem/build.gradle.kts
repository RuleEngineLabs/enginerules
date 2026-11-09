dependencies {
    api(project(":engine-application"))
    implementation(project(":adapter-persistence-documents"))
    implementation(project(":adapter-json-codec"))
    testImplementation(project(":adapter-expression-builtin"))
}
