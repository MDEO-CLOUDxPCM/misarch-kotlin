package org.misarch.tax

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original tax service's (`reference/sdl/tax.graphql`).
 */
class TaxSdlTest : SdlComparisonTest("tax")
