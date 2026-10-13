package org.misarch.shoppingcart

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original shopping cart service's (`reference/sdl/shoppingcart.graphql`).
 */
class ShoppingcartSdlTest : SdlComparisonTest("shoppingcart") {
    override val graphqlPath: String = "/"
}
