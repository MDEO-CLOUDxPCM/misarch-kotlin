package org.misarch.common

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.remote.withRetries
import kotlin.time.Duration.Companion.milliseconds

class RetriesTest {

    @Test
    fun `a call that succeeds after failures is retried with a pause per failure`() = runBlocking {
        var calls = 0
        val retried = mutableListOf<Int>()
        val result = withRetries(3, 1.milliseconds, { attempt, _ -> retried += attempt }) {
            calls++
            check(calls == 3) { "failure $calls" }
            "ok"
        }
        assertThat(result).isEqualTo("ok")
        assertThat(calls).isEqualTo(3)
        assertThat(retried).containsExactly(1, 2)
    }

    @Test
    fun `the failure of the last attempt propagates`() {
        var calls = 0
        assertThatThrownBy {
            runBlocking { withRetries(2, 1.milliseconds) { calls++; error("failure $calls") } }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("failure 3")
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `no retries means one attempt`() {
        var calls = 0
        assertThatThrownBy { runBlocking { withRetries(0, 1.milliseconds) { calls++; error("failure") } } }
        assertThat(calls).isEqualTo(1)
    }
}
