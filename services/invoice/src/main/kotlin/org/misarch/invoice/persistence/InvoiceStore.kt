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

/** A row of `user_address` (FIXED replica of the user addresses, docs/fixes/invoice.md). */
data class UserAddressRow(
    val id: UUID,
    val userId: UUID,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?,
    val firstName: String?,
    val lastName: String?,
)

/** A row of `users`, complete. */
data class UserRow(val id: UUID, val firstName: String, val lastName: String, val addresses: String, val userAddresses: String?)

/** SQL access to the tables of docs/specs/invoice.md §6 (the former Mongo collections). */
@Component
class InvoiceStore(private val db: DatabaseClient) {

    suspend fun findInvoice(id: UUID): InvoiceRow? =
        db.sql("SELECT * FROM invoices WHERE id = :id").bind("id", id).map(::toInvoice).awaitOneOrNull()

    /** `find_one({order_id})` in natural order. */
    suspend fun findInvoiceByOrderId(orderId: UUID): InvoiceRow? =
        db.sql("SELECT * FROM invoices WHERE order_id = :orderId ORDER BY seq LIMIT 1").bind("orderId", orderId)
            .map(::toInvoice).awaitOneOrNull()

    /** Inserts the invoice unless the order already has one (FIXED: one invoice per order); returns whether it did. */
    suspend fun insertInvoice(row: InvoiceRow): Boolean =
        db.sql(
            "INSERT INTO invoices (id, order_id, issued_at, content, user_address, vendor_address, vat_number) " +
                "VALUES (:id, :orderId, :issuedAt, :content, :userAddress, :vendorAddress, :vatNumber) ON CONFLICT (order_id) DO NOTHING"
        ).bind("id", row.id).bind("orderId", row.orderId).bind("issuedAt", row.issuedAt.atOffset(ZoneOffset.UTC))
            .bind("content", row.content).bind("userAddress", Json.of(row.userAddress))
            .bind("vendorAddress", Json.of(row.vendorAddress))
            .let { if (row.vatNumber != null) it.bind("vatNumber", row.vatNumber) else it.bindNull("vatNumber", String::class.java) }
            .fetch().awaitRowsUpdated() > 0

    /**
     * Upsert of the vendor address with all its fields (FIXED, D5: the original's upsert stored only `_id`, so the
     * address could never be used).
     */
    suspend fun upsertVendorAddress(
        id: UUID, street1: String, street2: String, city: String, postalCode: String, country: String, companyName: String?,
    ) {
        db.sql(
            "INSERT INTO vendor_address (id, street1, street2, city, postal_code, country, company_name) " +
                "VALUES (:id, :street1, :street2, :city, :postalCode, :country, :companyName) " +
                "ON CONFLICT (id) DO UPDATE SET street1 = EXCLUDED.street1, street2 = EXCLUDED.street2, city = EXCLUDED.city, " +
                "postal_code = EXCLUDED.postal_code, country = EXCLUDED.country, company_name = EXCLUDED.company_name"
        ).bind("id", id).bind("street1", street1).bind("street2", street2).bind("city", city).bind("postalCode", postalCode)
            .bind("country", country)
            .let { if (companyName != null) it.bind("companyName", companyName) else it.bindNull("companyName", String::class.java) }
            .fetch().awaitRowsUpdated()
    }

    /** The first complete vendor address (natural order, like the original's `find_one(None)`). */
    suspend fun findFirstVendorAddress(): VendorAddressRow? =
        db.sql("SELECT * FROM vendor_address WHERE street1 IS NOT NULL ORDER BY seq LIMIT 1").map { row ->
            VendorAddressRow(
                row.get("id", UUID::class.java)!!, row.get("street1", String::class.java),
                row.get("street2", String::class.java), row.get("city", String::class.java),
                row.get("postal_code", String::class.java), row.get("country", String::class.java),
                row.get("company_name", String::class.java),
            )
        }.awaitOneOrNull()

    /** `insert_one(User {_id, first_name, last_name, addresses: []})`; FIXED: a duplicate id is kept (idempotent). */
    suspend fun insertUser(id: UUID, firstName: String, lastName: String) {
        db.sql("INSERT INTO users (id, first_name, last_name, addresses) VALUES (:id, :first, :last, '[]'::jsonb) ON CONFLICT (id) DO NOTHING")
            .bind("id", id).bind("first", firstName).bind("last", lastName).fetch().awaitRowsUpdated()
    }

    /** Stores a user address (idempotent). */
    suspend fun insertUserAddress(a: UserAddressRow) {
        db.sql(
            "INSERT INTO user_address (id, user_id, street1, street2, city, postal_code, country, company_name, first_name, last_name) " +
                "VALUES (:id, :userId, :street1, :street2, :city, :postalCode, :country, :companyName, :firstName, :lastName) " +
                "ON CONFLICT (id) DO NOTHING"
        ).bind("id", a.id).bind("userId", a.userId).bind("street1", a.street1).bind("street2", a.street2).bind("city", a.city)
            .bind("postalCode", a.postalCode).bind("country", a.country)
            .let { spec -> listOf("companyName" to a.companyName, "firstName" to a.firstName, "lastName" to a.lastName)
                .fold(spec) { s, (k, v) -> if (v != null) s.bind(k, v) else s.bindNull(k, String::class.java) } }
            .fetch().awaitRowsUpdated()
    }

    /** Marks a user address as archived (kept: orders placed with it still get their invoice). */
    suspend fun archiveUserAddress(id: UUID, userId: UUID) {
        db.sql("UPDATE user_address SET archived = TRUE WHERE id = :id AND user_id = :userId")
            .bind("id", id).bind("userId", userId).fetch().awaitRowsUpdated()
    }

    suspend fun findUserAddress(id: UUID): UserAddressRow? =
        db.sql("SELECT * FROM user_address WHERE id = :id").bind("id", id).map { row ->
            UserAddressRow(
                row.get("id", UUID::class.java)!!, row.get("user_id", UUID::class.java)!!,
                row.get("street1", String::class.java)!!, row.get("street2", String::class.java)!!,
                row.get("city", String::class.java)!!, row.get("postal_code", String::class.java)!!,
                row.get("country", String::class.java)!!, row.get("company_name", String::class.java),
                row.get("first_name", String::class.java), row.get("last_name", String::class.java),
            )
        }.awaitOneOrNull()

    suspend fun findUser(id: UUID): UserRow? =
        db.sql("SELECT * FROM users WHERE id = :id").bind("id", id).map { row ->
            UserRow(
                row.get("id", UUID::class.java)!!, row.get("first_name", String::class.java)!!,
                row.get("last_name", String::class.java)!!, row.get("addresses", Json::class.java)!!.asString(),
                row.get("user_addresses", Json::class.java)?.asString(),
            )
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
