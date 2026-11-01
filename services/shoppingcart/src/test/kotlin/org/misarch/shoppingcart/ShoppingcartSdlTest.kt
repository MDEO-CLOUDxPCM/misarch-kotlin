package org.misarch.shoppingcart

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the reference SDL (`reference/sdl/shoppingcart.graphql`).
 */
class ShoppingcartSdlTest : SdlComparisonTest("shoppingcart") {
    override val graphqlPath: String = "/"
}
