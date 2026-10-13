package org.misarch.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.misarch.media.service.RustPath
import org.misarch.media.service.S3Presigner
import java.time.Instant

/** Unit tests: Rust `Path::file_stem` and the rust-s3 pre-signed URL shape. */
class MediaUnitTest {

    @Test
    fun `file_stem like Rust`() {
        assertEquals("a", RustPath.fileStem("a.png"))
        assertEquals("a.b", RustPath.fileStem("a.b.c"))
        assertEquals(".hidden", RustPath.fileStem(".hidden"))
        assertEquals(".", RustPath.fileStem("..hidden"))
        assertEquals("a", RustPath.fileStem("dir/a.png"))
        assertEquals("a", RustPath.fileStem("a."))
        assertEquals("noext", RustPath.fileStem("noext"))
        assertEquals("dir", RustPath.fileStem("dir/"))
        assertNull(RustPath.fileStem(".."))
        assertNull(RustPath.fileStem(""))
    }

    @Test
    fun `presigned URL shape and parameter order`() {
        val presigner = S3Presigner("http://media-minio:9000", "admin", "password", "eu-central-1")
        val url = presigner.presignGet("media-data", "0b9e.png", 86400, Instant.parse("2026-10-13T12:00:00Z"))
        val prefix = "http://media-minio:9000/media-data/0b9e.png?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=admin%2F20261013%2Feu-central-1%2Fs3%2Faws4_request&X-Amz-Date=20261013T120000Z" +
            "&X-Amz-Expires=86400&X-Amz-SignedHeaders=host&X-Amz-Signature="
        assertEquals(prefix, url.substring(0, prefix.length))
        assertEquals(64, url.length - prefix.length)
        // deterministic for a fixed time
        assertEquals(url, presigner.presignGet("media-data", "0b9e.png", 86400, Instant.parse("2026-10-13T12:00:00Z")))
        assertEquals("a%20b/c", S3Presigner.uriEncode("a b/c", false))
    }
}
