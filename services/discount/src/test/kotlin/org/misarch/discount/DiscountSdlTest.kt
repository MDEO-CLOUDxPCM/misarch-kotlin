package org.misarch.discount

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original discount service's (`reference/sdl/discount.graphql`).
 */
class DiscountSdlTest : SdlComparisonTest("discount")
