package org.misarch.review

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the reference SDL (`reference/sdl/review.graphql`).
 */
class ReviewSdlTest : SdlComparisonTest("review") {
    override val graphqlPath: String = "/"
}
