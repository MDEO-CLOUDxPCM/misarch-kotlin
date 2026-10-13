package org.misarch.common

import graphql.ExecutionInput
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.graphql.LegacyFederatedTracingConfiguration
import org.misarch.common.graphql.connection.PagingValidation

class PagingValidationTest {

    @Test
    fun `QUERYDSL rejects like com_querydsl QueryModifiers`() {
        PagingValidation.QUERYDSL.check(0, null)
        PagingValidation.QUERYDSL.check(5, 1)
        assertThatThrownBy { PagingValidation.QUERYDSL.check(0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("Limit must be greater than 0.")
        assertThatThrownBy { PagingValidation.QUERYDSL.check(-1, 0) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("Offset must not be negative.")
    }

    @Test
    fun `NONE accepts everything`() {
        PagingValidation.NONE.check(-1, -1)
    }

    @Test
    fun `legacy federated tracing traces unless the request opts out`() {
        fun input(context: Map<String, Any>): ExecutionInput =
            ExecutionInput.newExecutionInput("{ x }").graphQLContext(context).build()
        assertThat(LegacyFederatedTracingConfiguration.legacyShouldTrace(input(emptyMap()))).isTrue()
        assertThat(LegacyFederatedTracingConfiguration.legacyShouldTrace(input(mapOf("apollo-federation-include-trace" to "ftv1")))).isTrue()
        assertThat(LegacyFederatedTracingConfiguration.legacyShouldTrace(input(mapOf("apollo-federation-include-trace" to "no")))).isFalse()
    }
}
