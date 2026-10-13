package org.misarch.shipment

import org.misarch.common.testing.SdlComparisonTest
import org.springframework.test.context.TestPropertySource

/**
 * The federation SDL equals the original shipment service's (`reference/sdl/shipment.graphql`).
 * The provider endpoint is required at startup (like the original's schema script passes it).
 */
@TestPropertySource(properties = ["misarch.shipment.provider.endpoint=http://127.0.0.1:1/does-not-exist"])
class ShipmentSdlTest : SdlComparisonTest("shipment")
