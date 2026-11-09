import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("org.springframework.boot") version "4.1.2" apply false
    id("org.graalvm.buildtools.native") version "1.1.14" apply false
}

val springBootVersion = "4.1.2"

allprojects {
    group = "br.com.lucaslima"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-try,-processing", "-Werror", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val coverageGatedModules = setOf(
    "engine-domain",
    "engine-application",
    "adapter-expression-builtin",
    "adapter-json-codec",
    "adapter-persistence-documents",
    "adapter-persistence-filesystem",
    "adapter-persistence-aws",
    "adapter-http-connector",
    "adapter-grpc-connector",
    "adapter-cache-memory",
    "adapter-web",
)
val minimumLineCoverage = "0.98".toBigDecimal()

configure(subprojects.filter { it.name in coverageGatedModules }) {
    apply(plugin = "jacoco")

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.14"
    }

    val report = tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn("test")
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    val verification = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn("test")
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = minimumLineCoverage
                }
            }
        }
    }

    tasks.named("test") {
        finalizedBy(report)
    }

    tasks.named("check") {
        dependsOn(verification)
    }
}
