package org.misarch.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.misarch.common.testing.SdlCanonicalizer
import java.io.File

/**
 * Not a regular test: writes the canonical forms of reference/sdl and reference/schemas to
 * libs/common/build/reference-canonical/{sdl,schemas}/ for comparison (reference/README.md).
 * Run: tools/build.sh :libs:common:test --tests '*ReferenceSdlReport*' -Dmisarch.report=true
 */
class ReferenceSdlReport {

    @Test
    @EnabledIfSystemProperty(named = "misarch.report", matches = "true")
    fun `write canonical reference SDLs`() {
        val root = File(System.getProperty("misarch.reference.dir"))
        for (kind in listOf("sdl", "schemas")) {
            val out = File("build/reference-canonical/$kind").apply { mkdirs() }
            File(root, kind).listFiles { f -> f.name.endsWith(".graphql") }!!.forEach { file ->
                File(out, file.name).writeText(SdlCanonicalizer.canonicalize(file.readText()))
            }
        }
    }
}
