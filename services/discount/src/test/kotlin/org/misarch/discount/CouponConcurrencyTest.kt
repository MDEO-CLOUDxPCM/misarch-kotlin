package org.misarch.discount

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.PostgresTestDatabase
import tools.jackson.databind.JsonNode
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Concurrent redemptions of one coupon count every redemption and never exceed `maxUsages`. A trigger that reads
 * `usages` without a lock and writes back `usages + 1` loses updates (two redemptions that both read n both write
 * n + 1); both the parallel and the interleaved tests detect that.
 * `updateCoupon` saves a coupon loaded before a concurrent redemption without resetting `usages` (`@ReadOnlyProperty`).
 */
class CouponConcurrencyTest : DiscountTestSupport() {

    private fun couponWithUsers(maxUsages: Int?, users: Int): Triple<UUID, String, List<UUID>> {
        val couponId = createCoupon(createDiscount(categoryIds = listOf(registerCategory())), maxUsages = maxUsages ?: 1)
        if (maxUsages == null) {
            val cleared = graphql("""mutation { updateCoupon(input: {id: "$couponId", maxUsages: null}) { id } }""", user = employee)
            assertThat(cleared["errors"]).isNull()
        }
        return Triple(couponId, couponCode(couponId), List(users) { registerUser() })
    }

    private fun usages(couponId: UUID): Int = sql("SELECT usages FROM CouponEntity WHERE id = '$couponId'").single()["usages"] as Int

    private fun redemptions(couponId: UUID): Long =
        sql("SELECT COUNT(*) AS n FROM CouponRedemptionEntity WHERE couponId = '$couponId'").single()["n"] as Long

    private fun registerInParallel(code: String, users: List<UUID>): List<JsonNode> = runBlocking {
        users.map { user -> async(Dispatchers.IO) { registerCoupon(code, user, employee, selection = "id usages") } }.awaitAll()
    }

    @Test
    fun `parallel redemptions without a limit count every redemption`() {
        repeat(3) {
            val (couponId, code, users) = couponWithUsers(maxUsages = null, users = 40)
            val responses = registerInParallel(code, users)
            assertThat(responses).allSatisfy { assertThat(it["errors"]).isNull() }
            assertThat(redemptions(couponId)).isEqualTo(40L)
            assertThat(usages(couponId)).isEqualTo(40)
            assertThat(responses.map { it["data"]["registerCoupon"]["usages"].asInt() }).doesNotHaveDuplicates()
        }
    }

    @Test
    fun `parallel redemptions with maxUsages k succeed exactly k times`() {
        repeat(3) {
            val (couponId, code, users) = couponWithUsers(maxUsages = 10, users = 40)
            val (ok, failed) = registerInParallel(code, users).partition { it["errors"] == null }
            assertThat(ok).hasSize(10)
            assertThat(failed).allSatisfy { assertThat(errorMessage(it)).contains("Coupon has been used too often") }
            assertThat(redemptions(couponId)).isEqualTo(10L)
            assertThat(usages(couponId)).isEqualTo(10)
        }
    }

    // ------------------------------------------------- interleaved transactions (deterministic, plain JDBC sessions)

    private fun session(): Connection =
        DriverManager.getConnection(PostgresTestDatabase.jdbcUrl, "postgres", "postgres").apply { autoCommit = false }

    private fun Connection.redeem(couponId: UUID, userId: UUID) {
        prepareStatement("INSERT INTO CouponRedemptionEntity (couponId, userId) VALUES (?, ?)").use {
            it.setObject(1, couponId)
            it.setObject(2, userId)
            it.executeUpdate()
        }
    }

    /** Waits until [count] sessions wait for a lock (here: the coupon row locked by an uncommitted redemption). */
    private fun awaitLockWaits(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val waiting = sql(
                "SELECT COUNT(*) AS n FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()"
            ).single()["n"] as Long
            if (waiting >= count) return
            check(System.nanoTime() < deadline) { "no session waits for a lock" }
            Thread.sleep(20)
        }
    }

    /**
     * Session A redeems and keeps its transaction open; session B redeems meanwhile and has to wait for the coupon row.
     * Returns B's error (null if B succeeded) after A committed and B finished.
     */
    private fun interleavedRedemptions(couponId: UUID): SQLException? {
        val (userA, userB) = registerUser() to registerUser()
        session().use { a ->
            session().use { b ->
                a.redeem(couponId, userA)
                val second = CompletableFuture.supplyAsync {
                    try {
                        b.redeem(couponId, userB)
                        b.commit()
                        null
                    } catch (e: SQLException) {
                        b.rollback()
                        e
                    }
                }
                awaitLockWaits(1)
                a.commit()
                return second.get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `a redemption that waits for a concurrent one counts on top of it`() {
        val (couponId, _, _) = couponWithUsers(maxUsages = null, users = 0)
        val error = interleavedRedemptions(couponId)
        assertThat(error == null).withFailMessage { "second redemption failed: $error" }.isTrue()
        assertThat(redemptions(couponId)).isEqualTo(2L)
        assertThat(usages(couponId)).isEqualTo(2)
    }

    @Test
    fun `a redemption that waits for the last allowed one is rejected and rolled back`() {
        val (couponId, _, _) = couponWithUsers(maxUsages = 1, users = 0)
        assertThat(interleavedRedemptions(couponId)?.message).contains("Coupon has been used too often")
        assertThat(redemptions(couponId)).isEqualTo(1L)
        assertThat(usages(couponId)).isEqualTo(1)
    }

    @Test
    fun `updateCoupon does not overwrite usages incremented after it loaded the coupon`() {
        val (couponId, _, _) = couponWithUsers(maxUsages = 5, users = 0)
        val newCode = "C-" + UUID.randomUUID()
        session().use { a ->
            // the redemption's increment (usages 0 -> 1) is uncommitted: updateCoupon loads usages = 0, then its
            // UPDATE waits for the coupon row until the redemption commits
            a.redeem(couponId, registerUser())
            val update = CompletableFuture.supplyAsync {
                graphql("""mutation { updateCoupon(input: {id: "$couponId", code: "$newCode", maxUsages: 7}) { id code } }""", user = employee)
            }
            awaitLockWaits(1)
            a.commit()
            assertThat(update.get(10, TimeUnit.SECONDS)["errors"]).isNull()
        }
        val row = sql("SELECT usages, code, maxUsages FROM CouponEntity WHERE id = '$couponId'").single()
        assertThat(row["code"]).isEqualTo(newCode)
        assertThat(row["maxusages"]).isEqualTo(7)
        assertThat(row["usages"]).isEqualTo(1)
    }
}
