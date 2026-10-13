package org.misarch.invoice

import org.misarch.common.rust.RustCompatConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * Port of the MiSArch invoice service (original: Rust, axum + async-graphql + MongoDB).
 * Behaviour, defects and deviations: docs/specs/invoice.md.
 */
@SpringBootApplication
@Import(RustCompatConfiguration::class)
class InvoiceApplication

fun main(args: Array<String>) {
    runApplication<InvoiceApplication>(*args)
}
