package org.misarch.media

import org.misarch.common.rust.RustCompatConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * Port of the MiSArch media service (original: Rust, axum + async-graphql + rust-s3; blobs in MinIO, no database).
 * Behaviour, quirks and deviations: docs/specs/media.md.
 */
@SpringBootApplication
@Import(RustCompatConfiguration::class)
class MediaApplication

fun main(args: Array<String>) {
    runApplication<MediaApplication>(*args)
}
