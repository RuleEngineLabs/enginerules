dependencies {
    api(project(":engine-application"))
    implementation(project(":adapter-persistence-documents"))
    implementation(project(":adapter-json-codec"))
    api(platform("software.amazon.awssdk:bom:2.31.0"))
    api("software.amazon.awssdk:dynamodb")
    api("software.amazon.awssdk:s3")
}
