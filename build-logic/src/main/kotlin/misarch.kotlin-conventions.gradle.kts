// Convention for every Kotlin module (libs and services): Kotlin/JVM on the JDK toolchain from the version
// catalog, Spring all-open, Spring Boot BOM as platform, JUnit 6, lean test JVMs.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()
val javaVersion = libs.findVersion("java").get().requiredVersion

group = "org.misarch"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(javaVersion.toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion))
        javaParameters.set(true)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    // platforms: only recommend versions; explicit (higher) catalog versions win
    "implementation"(platform(lib("spring-boot-dependencies")))
    "implementation"(platform(lib("kotlinx-coroutines-bom")))
    "testImplementation"(platform(lib("testcontainers-bom")))
    "testImplementation"(platform(lib("junit-bom")))
    "testRuntimeOnly"(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // RAM is tight: one small test JVM per module, no parallel forks.
    maxParallelForks = 1
    maxHeapSize = "640m"
    jvmArgs("-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", "-XX:+EnableDynamicAgentLoading")
    // all images run in UTC; tests that format or truncate timestamps must not depend on the machine's zone
    jvmArgs("-Duser.timezone=UTC")
    systemProperty("misarch.reference.dir", rootProject.layout.projectDirectory.dir("reference").asFile.absolutePath)
    providers.systemProperty("misarch.report").orNull?.let { systemProperty("misarch.report", it) }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
