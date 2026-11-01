package org.misarch.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.misarch.catalog.service.duplicates

class DuplicatesTest {

    @Test
    fun `returns every element that occurs more than once`() {
        assertEquals(setOf("a", "c"), listOf("a", "b", "a", "c", "c", "c").duplicates())
        assertEquals(emptySet<String>(), listOf("a", "b").duplicates())
        assertEquals("[A]", listOf("A", "B", "A").duplicates().toString())
    }
}
