package org.misarch.common

import graphql.GraphQLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.misarch.common.graphql.dataloader.BaseIdDataLoader
import java.util.UUID
import java.util.concurrent.ExecutionException

class BaseIdDataLoaderTest {

    private val known = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val unknown = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private open class Loader(private val known: UUID) : BaseIdDataLoader<String, UUID>() {
        override suspend fun loadAll(ids: List<UUID>): List<UUID> = ids.filter { it == known }
        override fun toDTO(entity: UUID): String = entity.toString()
        override fun key(entity: UUID): UUID = entity
    }

    private class IndividualLoader(known: UUID) : Loader(known) {
        override val failMissingKeysIndividually = true
    }

    private fun cause(f: java.util.concurrent.CompletableFuture<String>): Throwable? =
        try {
            f.get(); null
        } catch (e: ExecutionException) {
            e.cause
        }

    @Test
    fun `default - a missing id fails the whole batch`() {
        val loader = Loader(known).getDataLoader(GraphQLContext.newContext().build())
        val a = loader.load(known)
        val b = loader.load(unknown)
        loader.dispatch()
        assertInstanceOf(NullPointerException::class.java, cause(a))
        assertInstanceOf(NullPointerException::class.java, cause(b))
    }

    @Test
    fun `opt-in - only the missing id fails, with NoSuchElementException without message`() {
        val loader = IndividualLoader(known).getDataLoader(GraphQLContext.newContext().build())
        val a = loader.load(known)
        val b = loader.load(unknown)
        loader.dispatch()
        assertEquals(known.toString(), a.get())
        val e = cause(b)
        assertInstanceOf(NoSuchElementException::class.java, e)
        assertNull(e!!.message)
    }
}
