// Root project: no code. Conventions live in build-logic/, versions in gradle/libs.versions.toml.
tasks.register("printServices") {
    group = "help"
    description = "Lists all service projects."
    val names = subprojects.filter { it.path.startsWith(":services:") }.map { it.name }
    doLast { names.forEach { println(it) } }
}
