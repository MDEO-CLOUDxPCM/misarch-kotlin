package org.misarch.inventory

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original inventory service's (`reference/sdl/inventory.graphql`).
 */
class InventorySdlTest : SdlComparisonTest("inventory")
