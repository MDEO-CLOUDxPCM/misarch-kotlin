package org.misarch.common.rust

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * [RustUuid.tryParse], [RustUuid.ORDER] and the [RustDateTime] formats as used by the shoppingcart, wishlist and order
 * ports (moved from their `CompatUnitTest` / `RustCompatTest`).
 */
class RustUuidAndTimeTest {

    private val id = UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001")

    @Test
    fun `uuid tryParse follows the Rust uuid crate`() {
        assertThat(RustUuid.tryParse("a1a1a1a1-0000-4000-8000-000000000001")).isEqualTo(id)
        assertThat(RustUuid.tryParse("A1A1A1A1-0000-4000-8000-000000000001")).isEqualTo(id)
        assertThat(RustUuid.tryParse("a1a1a1a1000040008000000000000001")).isEqualTo(id)
        assertThat(RustUuid.tryParse("{a1a1a1a1-0000-4000-8000-000000000001}")).isEqualTo(id)
        assertThat(RustUuid.tryParse("urn:uuid:a1a1a1a1-0000-4000-8000-000000000001")).isEqualTo(id)
        // accepted by java.util.UUID.fromString, rejected by Rust
        assertThat(RustUuid.tryParse("1-2-3-4-5")).isNull()
        assertThat(RustUuid.tryParse("a1a1a1a1-0000-4000-8000-00000000001")).isNull()
        assertThat(RustUuid.tryParse("a1a1a1a1-0000-4000-8000-0000000000001")).isNull()
        assertThat(RustUuid.tryParse("a1a1a1a10000-4000-8000-000000000001-")).isNull()
        assertThat(RustUuid.tryParse("g1a1a1a1-0000-4000-8000-000000000001")).isNull()
        assertThat(RustUuid.tryParse("URN:UUID:a1a1a1a1-0000-4000-8000-000000000001")).isNull()
        assertThat(RustUuid.tryParse("+1a1a1a1-0000-4000-8000-000000000001")).isNull()
        assertThat(RustUuid.tryParse("")).isNull()
        // order's former parseRustUuid cases
        val orderId = UUID.fromString("67e55044-10b1-426f-9247-bb680e5fe0c8")
        assertThat(RustUuid.tryParse("67e55044-10b1-426f-9247-bb680e5fe0c8")).isEqualTo(orderId)
        assertThat(RustUuid.tryParse("67E55044-10B1-426F-9247-BB680E5FE0C8")).isEqualTo(orderId)
        assertThat(RustUuid.tryParse("67e5504410b1426f9247bb680e5fe0c8")).isEqualTo(orderId)
        assertThat(RustUuid.tryParse("{67e55044-10b1-426f-9247-bb680e5fe0c8}")).isEqualTo(orderId)
        assertThat(RustUuid.tryParse("urn:uuid:67e55044-10b1-426f-9247-bb680e5fe0c8")).isEqualTo(orderId)
        assertThat(RustUuid.tryParse("1-1-1-1-1")).isNull()
        assertThat(RustUuid.tryParse("67e55044-10b1-426f-9247-bb680e5fe0cg")).isNull()
        assertThat(RustUuid.tryParse("67e5504410b1-426f-9247-bb680e5fe0c8-")).isNull()
        // non-ASCII never parses and never throws
        assertThat(RustUuid.tryParse("{ää" + "a".repeat(32) + "}")).isNull()
        assertThat(RustUuid.tryParse("a1a1a1a1-0000-4000-8000-00000000000١")).isNull()
    }

    @Test
    fun `uuid tryParse accepts exactly what parse accepts for ASCII input`() {
        val alphabet = "0123456789abcdefABCDEFgG-{}:urnid "
        val random = Random(7)
        val samples = mutableListOf<String>()
        listOf(
            "a1a1a1a1-0000-4000-8000-000000000001", "a1a1a1a1000040008000000000000001",
            "{a1a1a1a1-0000-4000-8000-000000000001}", "urn:uuid:a1a1a1a1-0000-4000-8000-000000000001",
        ).forEach { valid ->
            samples += valid
            repeat(400) {
                val chars = valid.toCharArray()
                chars[random.nextInt(chars.size)] = alphabet[random.nextInt(alphabet.length)]
                samples += String(chars)
            }
        }
        repeat(2000) { samples += String(CharArray(random.nextInt(30, 47)) { alphabet[random.nextInt(alphabet.length)] }) }
        for (s in samples) {
            assertThat(RustUuid.tryParse(s)).describedAs(s).isEqualTo(RustUuid.parseOrNull(s))
        }
    }

    @Test
    fun `uuid order is unsigned byte order`() {
        val low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
        val high = UUID.fromString("80000000-0000-0000-0000-000000000000")
        val max = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        val zero = UUID.fromString("00000000-0000-0000-0000-000000000000")
        val lowLsb = UUID.fromString("00000000-0000-0000-7fff-ffffffffffff")
        val highLsb = UUID.fromString("00000000-0000-0000-8000-000000000000")
        assertThat(listOf(max, high, highLsb, low, zero, lowLsb).sortedWith(RustUuid.ORDER))
            .containsExactly(zero, lowLsb, highLsb, low, high, max)
        assertThat(low.compareTo(high)).isGreaterThan(0) // java.util.UUID would sort them the other way round
        // order's former UuidBytesComparator cases
        val one = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val f0 = UUID.fromString("f0000000-0000-0000-0000-000000000000")
        assertThat(one.compareTo(f0)).isGreaterThan(0) // signed comparison in java.util.UUID
        assertThat(RustUuid.ORDER.compare(one, f0)).isLessThan(0)
        assertThat(RustUuid.ORDER.compare(highLsb, lowLsb)).isGreaterThan(0)
        assertThat(RustUuid.ORDER.compare(highLsb, highLsb)).isEqualTo(0)
    }

    @Test
    fun `instant timestamps use chrono formats`() {
        val millis = Instant.parse("2026-10-13T12:00:00.120Z")
        val whole = Instant.parse("2026-10-13T12:00:00Z")
        assertThat(RustDateTime.toRfc3339(millis)).isEqualTo("2026-10-13T12:00:00.120+00:00")
        assertThat(RustDateTime.toRfc3339(whole)).isEqualTo("2026-10-13T12:00:00+00:00")
        assertThat(RustDateTime.toSerde(millis)).isEqualTo("2026-10-13T12:00:00.120Z")
        assertThat(RustDateTime.toSerde(whole)).isEqualTo("2026-10-13T12:00:00Z")
        assertThat(RustDateTime.toSerde(Instant.parse("2026-10-13T12:00:00.000123Z"))).isEqualTo("2026-10-13T12:00:00.000123Z")
        assertThat(RustDateTime.now().nano % 1_000_000).isEqualTo(0)
    }
}
