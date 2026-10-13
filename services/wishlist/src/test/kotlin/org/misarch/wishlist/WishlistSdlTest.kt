package org.misarch.wishlist

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original wishlist service's (`reference/sdl/wishlist.graphql`).
 */
class WishlistSdlTest : SdlComparisonTest("wishlist") {
    override val graphqlPath: String = "/"
}
