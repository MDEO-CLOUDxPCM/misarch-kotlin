package org.misarch.common

import com.expediagroup.graphql.generator.TopLevelObject
import com.expediagroup.graphql.generator.federation.FederatedSchemaGeneratorConfig
import com.expediagroup.graphql.generator.federation.toFederatedSchema
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.idl.SchemaPrinter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.misarch.common.graphql.GraphQLConfiguration
import org.misarch.common.graphql.GraphQLDefaultValue
import java.util.Optional

class GraphQLDefaultValueTest {

    class Item(val id: Int) {
        fun children(@GraphQLDefaultValue("0") skip: Int? = 0, @GraphQLDefaultValue("2147483647") first: Int? = Int.MAX_VALUE): List<Int> =
            listOf(id)
    }

    class TestQuery : Query {
        fun items(@GraphQLDefaultValue("0") skip: Int? = 0, other: Int? = null): List<Item> = emptyList()
    }

    @Test
    fun `argument default values appear in the schema`() {
        val hooks = GraphQLConfiguration().federatedSchemaGeneratorHooks(Optional.empty(), Optional.empty())
        val schema = toFederatedSchema(
            FederatedSchemaGeneratorConfig(supportedPackages = listOf("org.misarch"), hooks = hooks),
            queries = listOf(TopLevelObject(TestQuery()))
        )
        val sdl = SchemaPrinter().print(schema)
        assertTrue(sdl.contains("items(other: Int, skip: Int = 0)") || sdl.contains("items(skip: Int = 0, other: Int)"), sdl)
        assertTrue(sdl.contains("first: Int = 2147483647"), sdl)
    }
}
