package org.misarch.shipment

import org.misarch.common.testing.SdlComparisonTest
import org.springframework.test.context.TestPropertySource

/**
 * The federation SDL equals the reference SDL (`reference/sdl/shipment.graphql`).
 * The provider endpoint is required at startup, so the test sets an unreachable one.
 */
@TestPropertySource(properties = ["misarch.shipment.provider.endpoint=http://127.0.0.1:1/does-not-exist"])
class ShipmentSdlTest : SdlComparisonTest("shipment")
