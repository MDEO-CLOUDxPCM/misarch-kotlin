package org.misarch.catalog

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original catalog service's (`reference/sdl/catalog.graphql`).
 */
class CatalogSdlTest : SdlComparisonTest("catalog")
