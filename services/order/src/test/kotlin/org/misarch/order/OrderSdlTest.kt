package org.misarch.order

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original order service's (`reference/sdl/order.graphql`).
 */
class OrderSdlTest : SdlComparisonTest("order") {
    override val graphqlPath: String = "/"
}
