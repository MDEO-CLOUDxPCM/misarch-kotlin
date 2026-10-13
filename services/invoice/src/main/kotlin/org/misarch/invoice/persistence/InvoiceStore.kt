package org.misarch.invoice.persistence

import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A row of `invoices`; the embedded addresses are JSON documents with the Mongo field names. */
data class InvoiceRow(
    val id: UUID,
    val orderId: UUID,
    val issuedAt: Instant,
    val content: String,
    val userAddress: String,
    val vendorAddress: String,
    val vatNumber: String?,
)

/** A row of `vendor_address` (fields are null for rows created by the upsert, which only sets `_id`). */
data class VendorAddressRow(
    val id: UUID,
    val street1: String?,
    val street2: String?,
    val city: String?,
    val postalCode: String?,
    val country: String?,
    val companyName: String?,
)

/** A row of `users`, complete. */
data class UserRow(val id: UUID, val firstName: String, val lastName: String, val addresses: String, val userAddresses: String?)

/**
 * Result of the projected address lookup `{"addresses.$": 1, "_id": 1}`: only the id and the matching address.
 * The projection never contains the names (always null), which is why the original fails to deserialize it (D6).
 */
data class ProjectedUser(
    val id: UUID,
    val matchingAddress: String,
    val firstName: String? = null,
    val lastName: String? = null,
)

/** SQL access to the tables of docs/specs/invoice.md §6 (the former Mongo collections). */
@Component
class InvoiceStore(private val db: DatabaseClient) {

    suspend fun findInvoice(id: UUID): InvoiceRow? =
        db.sql("SELECT * FROM invoices WHERE id = :id").bind("id", id).map(::toInvoice).awaitOneOrNull()

    /** `find_one({order_id})` in natural order. */
    suspend fun findInvoiceByOrderId(orderId: UUID): InvoiceRow? =
        db.sql("SELECT * FROM invoices WHERE order_id = :orderId ORDER BY seq LIMIT 1").bind("orderId", orderId)
            .map(::toInvoice).awaitOneOrNull()

    suspend fun insertInvoice(row: InvoiceRow) {
        db.sql(
            "INSERT INTO invoices (id, order_id, issued_at, content, user_address, vendor_address, vat_number) " +
                "VALUES (:id, :orderId, :issuedAt, :content, :userAddress, :vendorAddress, :vatNumber)"
        ).bind("id", row.id).bind("orderId", row.orderId).bind("issuedAt", row.issuedAt.atOffset(ZoneOffset.UTC))
            .bind("content", row.content).bind("userAddress", Json.of(row.userAddress))
            .bind("vendorAddress", Json.of(row.vendorAddress))
            .let { if (row.vatNumber != null) it.bind("vatNumber", row.vatNumber) else it.bindNull("vatNumber", String::class.java) }
            .fetch().awaitRowsUpdated()
    }

    /** `update_one({_id}, {$set: {_id}}, upsert)`: creates a row with only the id, keeps an existing one. */
    suspend fun upsertVendorAddressId(id: UUID) {
        db.sql("INSERT INTO vendor_address (id) VALUES (:id) ON CONFLICT (id) DO NOTHING").bind("id", id)
            .fetch().awaitRowsUpdated()
    }

    /** `find_one(None)` on `vendor_address` (natural order). */
    suspend fun findFirstVendorAddress(): VendorAddressRow? =
        db.sql("SELECT * FROM vendor_address ORDER BY seq LIMIT 1").map { row ->
            VendorAddressRow(
                row.get("id", UUID::class.java)!!, row.get("street1", String::class.java),
                row.get("street2", String::class.java), row.get("city", String::class.java),
                row.get("postal_code", String::class.java), row.get("country", String::class.java),
                row.get("company_name", String::class.java),
            )
        }.awaitOneOrNull()

    /** `insert_one(User {_id, first_name, last_name, addresses: []})`; a duplicate id fails. */
    suspend fun insertUser(id: UUID, firstName: String, lastName: String) {
        db.sql("INSERT INTO users (id, first_name, last_name, addresses) VALUES (:id, :first, :last, '[]'::jsonb)")
            .bind("id", id).bind("first", firstName).bind("last", lastName).fetch().awaitRowsUpdated()
    }

    suspend fun findUser(id: UUID): UserRow? =
        db.sql("SELECT * FROM users WHERE id = :id").bind("id", id).map { row ->
            UserRow(
                row.get("id", UUID::class.java)!!, row.get("first_name", String::class.java)!!,
                row.get("last_name", String::class.java)!!, row.get("addresses", Json::class.java)!!.asString(),
                row.get("user_addresses", Json::class.java)?.asString(),
            )
        }.awaitOneOrNull()

    /** `$push: {user_addresses: address}` on user [userId] (no upsert: unknown user → nothing). */
    suspend fun pushUserAddress(userId: UUID, addressJson: String) {
        db.sql(
            "UPDATE users SET user_addresses = COALESCE(user_addresses, '[]'::jsonb) || jsonb_build_array(:address::jsonb) " +
                "WHERE id = :id"
        ).bind("address", addressJson).bind("id", userId).fetch().awaitRowsUpdated()
    }

    /**
     * `find_one({addresses: {$elemMatch: {_id: addressId}}}, projection {"addresses.$": 1, "_id": 1})`:
     * the first user whose `addresses` contains the address, projected to id + that address.
     */
    suspend fun findUserByAddress(addressId: UUID): ProjectedUser? =
        db.sql(
            "SELECT u.id, a.address FROM users u, LATERAL jsonb_array_elements(u.addresses) WITH ORDINALITY a(address, i) " +
                "WHERE a.address ->> '_id' = :addressId ORDER BY u.seq, a.i LIMIT 1"
        ).bind("addressId", addressId.toString()).map { row ->
            ProjectedUser(row.get("id", UUID::class.java)!!, row.get("address", Json::class.java)!!.asString())
        }.awaitOneOrNull()

    private fun toInvoice(row: Readable) = InvoiceRow(
        id = row.get("id", UUID::class.java)!!,
        orderId = row.get("order_id", UUID::class.java)!!,
        issuedAt = row.get("issued_at", OffsetDateTime::class.java)!!.toInstant(),
        content = row.get("content", String::class.java)!!,
        userAddress = row.get("user_address", Json::class.java)!!.asString(),
        vendorAddress = row.get("vendor_address", Json::class.java)!!.asString(),
        vatNumber = row.get("vat_number", String::class.java),
    )
}
