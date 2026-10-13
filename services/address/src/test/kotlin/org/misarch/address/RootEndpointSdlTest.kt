package org.misarch.address

import org.misarch.common.testing.SdlComparisonTest
import org.springframework.test.context.TestPropertySource

/**
 * Template check for the ports of the Rust services (served at `/` instead of `/graphql`):
 * `graphql.endpoint: /` serves the same schema at the root path.
 */
@TestPropertySource(properties = ["graphql.endpoint=/"])
class RootEndpointSdlTest : SdlComparisonTest("address") {
    override val graphqlPath: String = "/"
}
