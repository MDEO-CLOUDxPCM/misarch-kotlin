package org.misarch.review

import org.misarch.common.rust.RustCompatConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * Port of the MiSArch review service (original: Rust, axum + async-graphql + MongoDB).
 * Behaviour, quirks and deviations: docs/specs/review.md.
 */
@SpringBootApplication
@Import(RustCompatConfiguration::class)
class ReviewApplication

fun main(args: Array<String>) {
    runApplication<ReviewApplication>(*args)
}
