package org.misarch.common

import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.graphql.MisarchDataFetcherExceptionHandler

class GraphQLErrorsTest {

    private val graphQL = run {
        val registry = SchemaParser().parse("type Query { plain: String default: String }")
        val wiring = RuntimeWiring.newRuntimeWiring().type("Query") {
            it.dataFetcher("plain", DataFetcher<String> { throw GraphQLErrorException("User not found") })
                .dataFetcher("default", DataFetcher<String> { throw IllegalStateException("Unauthorized access") })
        }.build()
        GraphQL.newGraphQL(SchemaGenerator().makeExecutableSchema(registry, wiring))
            .defaultDataFetcherExceptionHandler(MisarchDataFetcherExceptionHandler())
            .build()
    }

    @Test
    fun `plain message for GraphQLErrorException, default message otherwise`() {
        assertEquals("User not found", graphQL.execute("{ plain }").errors.single().message)
        assertEquals(
            "Exception while fetching data (/default) : Unauthorized access",
            graphQL.execute("{ default }").errors.single().message
        )
    }
}
