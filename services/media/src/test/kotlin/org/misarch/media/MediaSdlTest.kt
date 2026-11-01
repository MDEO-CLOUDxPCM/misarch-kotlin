package org.misarch.media

import org.misarch.common.testing.SdlComparisonTest
import org.springframework.test.context.TestPropertySource

/**
 * The federation SDL equals the reference SDL (`reference/sdl/media.graphql`).
 */
@TestPropertySource(properties = ["MINIO_ENDPOINT=http://127.0.0.1:1"])
class MediaSdlTest : SdlComparisonTest("media") {
    override val graphqlPath: String = "/"
}
