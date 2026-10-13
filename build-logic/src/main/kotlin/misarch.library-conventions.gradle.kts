// Convention for shared libraries (libs/*): Kotlin + java-library + test fixtures.
plugins {
    id("misarch.kotlin-conventions")
    `java-library`
    `java-test-fixtures`
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "api"(platform(lib("spring-boot-dependencies")))
    "api"(platform(lib("kotlinx-coroutines-bom")))
    "testFixturesApi"(platform(lib("spring-boot-dependencies")))
    "testFixturesApi"(platform(lib("testcontainers-bom")))
    "testFixturesApi"(platform(lib("junit-bom")))
}
