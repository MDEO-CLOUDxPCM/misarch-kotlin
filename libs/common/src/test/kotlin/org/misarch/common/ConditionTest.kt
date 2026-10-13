package org.misarch.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.SqlParameters

class ConditionTest {

    @Test
    fun `renders unique named parameters`() {
        val condition = Condition.eq("userId", 1) and (Condition.isNull("archivedAt") or Condition.inList("id", listOf(2, 3)))
        val params = SqlParameters()
        assertEquals("(userId = :p0) AND ((archivedAt IS NULL) OR (id IN (:p1)))", params.render(condition))
        assertEquals(mapOf("p0" to 1, "p1" to listOf(2, 3)), params.values)
        assertEquals("FALSE", Condition.inList("id", emptyList()).sql)
        assertEquals(null, Condition.allOf(null, null))
    }
}
