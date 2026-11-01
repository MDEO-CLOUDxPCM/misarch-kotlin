package org.misarch.media

import org.misarch.common.testing.SdlComparisonTest
import org.springframework.test.context.TestPropertySource

/**
 * The federation SDL equals the reference SDL (`reference/sdl/media.graphql`). The test runs without a store: the
 * bucket is not created.
 */
@TestPropertySource(properties = ["MINIO_ENDPOINT=http://127.0.0.1:1", "misarch.media.create-bucket=false"])
class MediaSdlTest : SdlComparisonTest("media") {
    override val graphqlPath: String = "/"
}
