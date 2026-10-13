// Convention for every service (services/<name>): Spring Boot application using libs/common.
// Produces build/libs/app.jar (bootJar); tools/image.sh turns it into misarch-kotlin/<name>:dev.
plugins {
    id("misarch.kotlin-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "implementation"(project(":libs:common"))
    "testImplementation"(testFixtures(project(":libs:common")))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

tasks.named<Jar>("jar") {
    enabled = false
}

// Writes the service's federation SDL (`{ _service { sdl } }`) to build/sdl/<name>.graphql and compares it
// semantically with reference/sdl/<name>.graphql. Used by tools/sdl-diff.sh.
tasks.register<Test>("sdlTest") {
    group = "verification"
    description = "Runs the SDL comparison test (*SdlTest) only."
    testClassesDirs = project.the<SourceSetContainer>()["test"].output.classesDirs
    classpath = project.the<SourceSetContainer>()["test"].runtimeClasspath
    filter { includeTestsMatching("*SdlTest") }
    // -Pmisarch.sdl.descriptions=false: ignore descriptions; -Pmisarch.sdl.reference=schemas: compare with
    // reference/schemas (misarch/schemas repo) instead of reference/sdl (live _service SDL of the original)
    systemProperty("misarch.sdl.descriptions", providers.gradleProperty("misarch.sdl.descriptions").getOrElse("true"))
    systemProperty("misarch.sdl.reference", providers.gradleProperty("misarch.sdl.reference").getOrElse("sdl"))
    outputs.upToDateWhen { false }
}
