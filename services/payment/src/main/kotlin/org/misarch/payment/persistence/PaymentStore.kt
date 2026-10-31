package org.misarch.payment.persistence

import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A `paymentinformations` document. JSON columns hold the original JSON text. */
data class PaymentInformationRow(
    val id: String,
    val paymentMethod: String,
    val publicMethodDetails: String?,
    val secretMethodDetails: String?,
    val userId: String?,
)

/** A `payments` document. [paymentInformation] is the referenced payment information id. */
data class PaymentRow(
    val id: String,
    val totalAmount: Double,
    val status: String,
    val paymentInformation: String,
    val payedAt: Instant?,
    val numberOfRetries: Double?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** An equality condition of the original Mongo filters (absent / `null` / value). */
sealed interface Match {
    data object Absent : Match
    data object Null : Match
    data object Never : Match
    data class Value(val value: String) : Match
}

/** Sort of the original (`sort({[field]: direction})`): by id (the only order field). */
enum class Sort(val sql: String) { ID_ASC("id ASC"), ID_DESC("id DESC") }

/** The payment query of `buildQuery` (truthy filter fields only). */
data class PaymentQuery(
    val status: String? = null,
    val paymentInformationIn: List<String>? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

/**
 * SQL access to the three former Mongo collections of payment (docs/specs/payment.md §8).
 */
@Repository
class PaymentStore(private val db: DatabaseClient) {

    private fun instant(row: Readable, column: String): Instant? =
        row.get(column, OffsetDateTime::class.java)?.toInstant()

    private fun json(row: Readable, column: String): String? = row.get(column, Json::class.java)?.asString()

    private fun mapInfo(row: Readable) = PaymentInformationRow(
        id = row.get("id", String::class.java)!!,
        paymentMethod = row.get("paymentmethod", String::class.java)!!,
        publicMethodDetails = json(row, "publicmethoddetails"),
        secretMethodDetails = json(row, "secretmethoddetails"),
        userId = row.get("userid", String::class.java),
    )

    private fun mapPayment(row: Readable) = PaymentRow(
        id = row.get("id", String::class.java)!!,
        totalAmount = row.get("totalamount", java.lang.Double::class.java)!!.toDouble(),
        status = row.get("status", String::class.java)!!,
        paymentInformation = row.get("paymentinformation", String::class.java)!!,
        payedAt = instant(row, "payedat"),
        numberOfRetries = row.get("numberofretries", java.lang.Double::class.java)?.toDouble(),
        createdAt = instant(row, "createdat")!!,
        updatedAt = instant(row, "updatedat")!!,
    )

    private fun ts(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    // ---- payment informations ----

    suspend fun insertInformation(row: PaymentInformationRow) {
        var spec = db.sql(
            "INSERT INTO PaymentInformationEntity (id, paymentMethod, publicMethodDetails, secretMethodDetails, userId) " +
                "VALUES (:id, :method, :public, :secret, :userId)"
        ).bind("id", row.id).bind("method", row.paymentMethod)
        spec = row.publicMethodDetails?.let { spec.bind("public", Json.of(it)) } ?: spec.bindNull("public", Json::class.java)
        spec = row.secretMethodDetails?.let { spec.bind("secret", Json.of(it)) } ?: spec.bindNull("secret", Json::class.java)
        spec = row.userId?.let { spec.bind("userId", it) } ?: spec.bindNull("userId", String::class.java)
        spec.fetch().awaitRowsUpdated()
    }

    suspend fun findInformation(id: String): PaymentInformationRow? =
        db.sql("SELECT * FROM PaymentInformationEntity WHERE id = :id").bind("id", id)
            .map(::mapInfo).one().awaitFirstOrNull()

    /** The informations with the given ids (missing ids are absent; one statement). */
    suspend fun findInformations(ids: Collection<String>): List<PaymentInformationRow> =
        db.sql("SELECT * FROM PaymentInformationEntity WHERE id = ANY(:ids)").bind("ids", ids.toTypedArray())
            .map(::mapInfo).all().collectList().awaitSingle()

    suspend fun deleteInformation(id: String): PaymentInformationRow? =
        db.sql("DELETE FROM PaymentInformationEntity WHERE id = :id RETURNING *").bind("id", id)
            .map(::mapInfo).one().awaitFirstOrNull()

    /** True if [userId] has a payment information with [method]. */
    suspend fun informationExists(userId: String, method: String): Boolean =
        db.sql("SELECT 1 FROM PaymentInformationEntity WHERE userId = :userId AND paymentMethod = :method LIMIT 1")
            .bind("userId", userId).bind("method", method)
            .map { _ -> 1 }.one().awaitFirstOrNull() != null

    /** Transaction-scoped advisory lock serializing the default-information creation of one user. */
    suspend fun lockUser(userId: String) {
        db.sql("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) AS l").bind("key", "payment-user:$userId")
            .map { _ -> 1 }.one().awaitFirstOrNull()
    }

    private fun informationWhere(user: Match, method: Match, binds: MutableMap<String, String>): String? {
        val conditions = mutableListOf<String>()
        for ((column, match) in listOf("userId" to user, "paymentMethod" to method)) {
            when (match) {
                Match.Absent -> {}
                Match.Null -> conditions += "$column IS NULL"
                Match.Never -> return null
                is Match.Value -> {
                    conditions += "$column = :$column"
                    binds[column] = match.value
                }
            }
        }
        return if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
    }

    /** `find(filter).limit(first).skip(skip).sort(...)`; [first]/[skip] null = no limit / no skip. */
    suspend fun findInformations(user: Match, method: Match, sort: Sort, first: Long?, skip: Long?): List<PaymentInformationRow> {
        val binds = mutableMapOf<String, String>()
        val where = informationWhere(user, method, binds) ?: return emptyList()
        var spec = db.sql(
            "SELECT * FROM PaymentInformationEntity$where ORDER BY ${sort.sql}" +
                (if (first != null) " LIMIT $first" else "") + (if (skip != null) " OFFSET $skip" else "")
        )
        binds.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec.map(::mapInfo).all().collectList().awaitSingle()
    }

    suspend fun countInformations(user: Match, method: Match): Long {
        val binds = mutableMapOf<String, String>()
        val where = informationWhere(user, method, binds) ?: return 0
        var spec = db.sql("SELECT COUNT(*) AS n FROM PaymentInformationEntity$where")
        binds.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec.map { row -> (row.get("n") as Number).toLong() }.one().awaitSingle()
    }

    // ---- payments ----

    suspend fun insertPayment(row: PaymentRow) {
        db.sql(
            "INSERT INTO PaymentEntity (id, totalAmount, status, paymentInformation, numberOfRetries, createdAt, updatedAt) " +
                "VALUES (:id, :amount, :status, :info, :retries, :createdAt, :updatedAt)"
        ).bind("id", row.id).bind("amount", row.totalAmount).bind("status", row.status)
            .bind("retries", row.numberOfRetries ?: 0.0)
            .bind("info", row.paymentInformation).bind("createdAt", ts(row.createdAt)).bind("updatedAt", ts(row.updatedAt))
            .fetch().awaitRowsUpdated()
    }

    suspend fun findPayment(id: String): PaymentRow? =
        db.sql("SELECT * FROM PaymentEntity WHERE id = :id").bind("id", id)
            .map(::mapPayment).one().awaitFirstOrNull()

    suspend fun deletePayment(id: String): PaymentRow? =
        db.sql("DELETE FROM PaymentEntity WHERE id = :id RETURNING *").bind("id", id)
            .map(::mapPayment).one().awaitFirstOrNull()

    /**
     * `findOneAndUpdate({_id}, {$set: {status, payedAt?, updatedAt}}, {new: true})`. With [onlyIfChanged] the row is
     * only updated (and returned) if its status differs from [status] (a transition happens exactly once).
     */
    suspend fun updateStatus(id: String, status: String, payedAt: Instant?, now: Instant, onlyIfChanged: Boolean = false): PaymentRow? {
        val sql = "UPDATE PaymentEntity SET status = :status, updatedAt = :now" +
            (if (payedAt != null) ", payedAt = :payedAt" else "") + " WHERE id = :id" +
            (if (onlyIfChanged) " AND status <> :status" else "") + " RETURNING *"
        var spec = db.sql(sql).bind("id", id).bind("status", status).bind("now", ts(now))
        if (payedAt != null) spec = spec.bind("payedAt", ts(payedAt))
        return spec.map(::mapPayment).one().awaitFirstOrNull()
    }

    /**
     * Counts one credit-card retry: `numberOfRetries + 1` if it is still below [limit] (atomic); null if the limit is
     * reached (or the payment does not exist).
     */
    suspend fun incrementRetries(id: String, limit: Int, now: Instant): PaymentRow? =
        db.sql(
            "UPDATE PaymentEntity SET numberOfRetries = COALESCE(numberOfRetries, 0) + 1, updatedAt = :now " +
                "WHERE id = :id AND COALESCE(numberOfRetries, 0) < :limit RETURNING *"
        ).bind("id", id).bind("now", ts(now)).bind("limit", limit.toDouble())
            .map(::mapPayment).one().awaitFirstOrNull()

    private fun paymentWhere(query: PaymentQuery, binds: MutableMap<String, Any>): String? {
        val conditions = mutableListOf<String>()
        query.status?.let { conditions += "status = :status"; binds["status"] = it }
        query.paymentInformationIn?.let {
            if (it.isEmpty()) return null
            conditions += "paymentInformation = ANY(:infos)"
            binds["infos"] = it.toTypedArray()
        }
        query.from?.let { conditions += "createdAt >= :from"; binds["from"] = ts(it) }
        query.to?.let { conditions += "createdAt <= :to"; binds["to"] = ts(it) }
        return if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
    }

    suspend fun findPayments(query: PaymentQuery, sort: Sort, first: Long?, skip: Long?): List<PaymentRow> {
        val binds = mutableMapOf<String, Any>()
        val where = paymentWhere(query, binds) ?: return emptyList()
        var spec = db.sql(
            "SELECT * FROM PaymentEntity$where ORDER BY ${sort.sql}" +
                (if (first != null) " LIMIT $first" else "") + (if (skip != null) " OFFSET $skip" else "")
        )
        binds.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec.map(::mapPayment).all().collectList().awaitSingle()
    }

    suspend fun countPayments(query: PaymentQuery): Long {
        val binds = mutableMapOf<String, Any>()
        val where = paymentWhere(query, binds) ?: return 0
        var spec = db.sql("SELECT COUNT(*) AS n FROM PaymentEntity$where")
        binds.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec.map { row -> (row.get("n") as Number).toLong() }.one().awaitSingle()
    }

    // ---- open orders ----

    /** Stores the open order; false if an open order for [orderId] already exists (null ids never conflict). */
    suspend fun insertOpenOrder(paymentId: String, orderId: String?, orderJson: String): Boolean {
        var spec = db.sql(
            "INSERT INTO OpenOrderEntity (paymentId, orderId, orderJson) VALUES (:paymentId, :orderId, :order) " +
                "ON CONFLICT (orderId) DO NOTHING"
        ).bind("paymentId", paymentId).bind("order", Json.of(orderJson))
        spec = orderId?.let { spec.bind("orderId", it) } ?: spec.bindNull("orderId", String::class.java)
        return spec.fetch().awaitRowsUpdated() > 0
    }

    suspend fun findOpenOrder(paymentId: String?): String? {
        val spec = if (paymentId == null) db.sql("SELECT orderJson FROM OpenOrderEntity WHERE paymentId IS NULL ORDER BY id LIMIT 1")
        else db.sql("SELECT orderJson FROM OpenOrderEntity WHERE paymentId = :paymentId ORDER BY id LIMIT 1").bind("paymentId", paymentId)
        return spec.map { row -> json(row, "orderjson") ?: "null" }.one().awaitFirstOrNull()
    }

    suspend fun deleteOpenOrder(paymentId: String?): String? {
        val spec = if (paymentId == null) {
            db.sql("DELETE FROM OpenOrderEntity WHERE id = (SELECT id FROM OpenOrderEntity WHERE paymentId IS NULL ORDER BY id LIMIT 1) RETURNING orderJson")
        } else {
            db.sql("DELETE FROM OpenOrderEntity WHERE id = (SELECT id FROM OpenOrderEntity WHERE paymentId = :paymentId ORDER BY id LIMIT 1) RETURNING orderJson")
                .bind("paymentId", paymentId)
        }
        return spec.map { row -> json(row, "orderjson") ?: "null" }.one().awaitFirstOrNull()
    }
}
