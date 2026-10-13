package org.misarch.payment.service

import org.misarch.common.nest.Js
import org.misarch.payment.nest.JsDate
import org.misarch.common.nest.JsError
import org.misarch.common.nest.MongooseCast
import org.misarch.common.nest.NotFoundException
import org.misarch.common.nest.UnauthorizedException
import org.misarch.payment.persistence.Match
import org.misarch.payment.persistence.PaymentInformationRow
import org.misarch.payment.persistence.PaymentQuery
import org.misarch.payment.persistence.PaymentRow
import org.misarch.payment.persistence.PaymentStore
import org.misarch.payment.persistence.Sort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

object PaymentMethods {
    const val INVOICE = "INVOICE"
    const val PREPAYMENT = "PREPAYMENT"
    const val CREDIT_CARD = "CREDIT_CARD"
}

object PaymentStatuses {
    const val OPEN = "OPEN"
    const val PENDING = "PENDING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
}

/** A payment with its populated payment information (`null` = the referenced information no longer exists). */
data class PopulatedPayment(val row: PaymentRow, val info: PaymentInformationRow?)

/**
 * `sort({[orderBy.field]: orderBy.direction})` of the original without defaults: `orderBy` null → `_id` ascending;
 * missing field → sort key `undefined` (natural order); missing direction → Mongoose's "Invalid sort value".
 */
fun sortOf(orderBy: Map<String, Any?>?): Sort {
    if (orderBy == null) return Sort.ID_ASC
    val field = orderBy["field"]?.let { if (it is Enum<*>) it.name else it.toString() }
    val key = if (field == null) "undefined" else "_id"
    val direction = orderBy["direction"]?.let { if (it is Enum<*>) it.name else it.toString() }
        ?: throw JsError("Invalid sort value: { $key: undefined }")
    return when {
        key == "undefined" -> Sort.NATURAL
        direction == "DESC" -> Sort.ID_DESC
        else -> Sort.ID_ASC
    }
}

/** The original `PaymentInformationService` (docs/specs/payment.md §4.2). */
@Service
class PaymentInformationService(private val store: PaymentStore) {
    private val logger = LoggerFactory.getLogger(PaymentInformationService::class.java)
    private val mapper = JsonMapper.builder().build()

    /** `createCreditCardInformation(input, {id: currentUser.id})` */
    suspend fun createCreditCardInformation(input: Map<String, Any?>, userId: String?): PaymentInformationRow {
        val cardNumber = input["cardNumber"] as String
        val publicDetails = linkedMapOf(
            "cardHolder" to input["cardHolder"],
            // mask every digit that is followed by 4 more digits
            "cardNumber" to cardNumber.replace(Regex("\\d(?=\\d{4})"), "*"),
            "expirationDate" to input["expirationDate"],
        )
        val row = PaymentInformationRow(
            id = UUID.randomUUID().toString(),
            paymentMethod = PaymentMethods.CREDIT_CARD,
            publicMethodDetails = mapper.writeValueAsString(publicDetails),
            secretMethodDetails = mapper.writeValueAsString(input),
            userId = userId,
        )
        store.insertInformation(row)
        return row
    }

    suspend fun createPaymentInformation(paymentMethod: String, userId: String?): PaymentInformationRow {
        val row = PaymentInformationRow(UUID.randomUUID().toString(), paymentMethod, null, null, userId)
        store.insertInformation(row)
        return row
    }

    /** Creates the default payment informations (prepayment, then invoice) of a new user. */
    suspend fun addDefaultPaymentInformations(userId: String?) {
        logger.info("{addDefaultPaymentInformations} for user: {}", userId)
        createPaymentInformation(PaymentMethods.PREPAYMENT, userId)
        createPaymentInformation(PaymentMethods.INVOICE, userId)
    }

    suspend fun find(user: Match, method: Match, orderBy: Map<String, Any?>?, first: Long?, skip: Long?) =
        store.findInformations(user, method, sortOf(orderBy), first, skip)

    suspend fun count(user: Match, method: Match) = store.countInformations(user, method)

    suspend fun findById(id: String): PaymentInformationRow =
        store.findInformation(id) ?: throw NotFoundException("Payment Information with id \"$id\" not found")

    /** Ids of all payment informations with [method] (`find({filter: {paymentMethod}})`, unbounded). */
    suspend fun idsWithMethod(method: String): List<String> =
        store.findInformations(Match.Absent, Match.Value(method), Sort.ID_ASC, null, null).map { it.id }

    /**
     * `delete(_id, user, roles)`: buyers never pass the owner check because the stored user is an object
     * (`paymentInfo.user.toString()` is `[object Object]`).
     */
    suspend fun delete(id: String, currentUserId: JsonNode?, roles: JsonNode?): PaymentInformationRow {
        val info = store.findInformation(id) ?: throw NotFoundException("Payment Information with id \"$id\" not found")
        if (!hasEmployeeRole(roles) && "[object Object]" != jsToString(currentUserId)) {
            throw UnauthorizedException("User not authorized to delete Payment Information with id \"$id\"")
        }
        return store.deleteInformation(id) ?: throw JsError("Cannot return null for non-nullable field Mutation.deletePaymentInformation.")
    }

    companion object {
        /** `roles.some((role) => [admin, employee].includes(role))` */
        fun hasEmployeeRole(roles: JsonNode?): Boolean = when {
            roles == null || roles.isMissingNode -> throw JsError("Cannot read properties of undefined (reading 'some')")
            roles.isNull -> throw JsError("Cannot read properties of null (reading 'some')")
            roles.isArray -> roles.any { it.isString && (it.asString() == "admin" || it.asString() == "employee") }
            else -> throw JsError("roles.some is not a function")
        }

        /** `x.toString()` of the current user's id (TypeError for undefined/null). */
        fun jsToString(value: JsonNode?): String = when {
            value == null || value.isMissingNode -> throw JsError("Cannot read properties of undefined (reading 'toString')")
            value.isNull -> throw JsError("Cannot read properties of null (reading 'toString')")
            else -> Js.toJsString(value)
        }
    }
}

/** The original `PaymentService` (docs/specs/payment.md §4.3, §5.4). */
@Service
class PaymentService(
    private val store: PaymentStore,
    private val paymentInformationService: PaymentInformationService,
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    private suspend fun populate(row: PaymentRow) = PopulatedPayment(row, store.findInformation(row.paymentInformation))

    /** `buildQuery(filter)` (truthiness checks); an Invalid Date fails like Mongoose's cast. */
    suspend fun buildQuery(filter: Map<String, Any?>?): PaymentQuery {
        if (filter == null) return PaymentQuery()
        val status = filter["status"]?.let { if (it is Enum<*>) it.name else it.toString() }
        val paymentInformationId = (filter["paymentInformationId"] as String?)?.takeIf { it.isNotEmpty() }
        val paymentMethod = filter["paymentMethod"]?.let { if (it is Enum<*>) it.name else it.toString() }
        val allowed = if (paymentInformationId != null || paymentMethod != null) {
            buildAllowedFilterIds(paymentInformationId, paymentMethod)
        } else null
        fun date(name: String) = (filter[name] as JsDate?)?.let {
            it.instant ?: throw JsError("Cast to date failed for value \"Invalid Date\" (type Date) at path \"createdAt\" for model \"Payment\"")
        }
        return PaymentQuery(status, allowed, date("from"), date("to"))
    }

    private suspend fun buildAllowedFilterIds(paymentInformationId: String?, paymentMethod: String?): List<String> {
        if (paymentMethod == null) return listOf(paymentInformationId!!)
        val ids = paymentInformationService.idsWithMethod(paymentMethod)
        if (paymentInformationId == null) return ids
        if (paymentInformationId !in ids) return emptyList()
        return listOf(paymentInformationId)
    }

    suspend fun find(filter: Map<String, Any?>?, orderBy: Map<String, Any?>?, first: Long?, skip: Long?): List<PopulatedPayment> {
        val query = buildQuery(filter)
        val sort = sortOf(orderBy)
        return store.findPayments(query, sort, first, skip).map { populate(it) }
    }

    suspend fun count(filter: Map<String, Any?>?): Long = store.countPayments(buildQuery(filter))

    suspend fun findById(id: String): PopulatedPayment =
        store.findPayment(id)?.let { populate(it) } ?: throw NotFoundException("Payment with id \"$id\" not found")

    /** `delete`: the deleted document is returned **unpopulated**. */
    suspend fun delete(id: String): PaymentRow {
        store.findPayment(id) ?: throw NotFoundException("Payment with id \"$id\" not found")
        return store.deletePayment(id) ?: throw JsError("Cannot return null for non-nullable field Mutation.deletePayment.")
    }

    /** `create(order)`: looks up the payment information and stores an OPEN payment. */
    suspend fun create(order: JsonNode): Pair<PaymentRow, PaymentInformationRow> {
        val paymentInformationId = Js.toJsString(order.get("paymentInformationId"))
        logger.info("{create} Creating payment for order \"{}\" with paymentInformationId \"{}\"", order.get("id"), paymentInformationId)
        val info = paymentInformationService.findById(paymentInformationId)
        val totalAmount = MongooseCast.number(order.get("compensatableOrderAmount"), "totalAmount", "Payment")
        val now = Js.now()
        val row = PaymentRow(UUID.randomUUID().toString(), totalAmount, PaymentStatuses.OPEN, info.id, null, null, now, now)
        store.insertPayment(row)
        return row to info
    }

    /** `updatePaymentStatus(_id, status)`: sets `payedAt` for SUCCEEDED; returns the populated payment. */
    suspend fun updatePaymentStatus(id: String, status: String): PopulatedPayment {
        logger.info("{updatePaymentStatus} Updating payment status for id: \"{}\" to {}", id, status)
        val now = Js.now()
        val row = store.updateStatus(id, status, if (status == PaymentStatuses.SUCCEEDED) now else null, now)
            ?: throw NotFoundException("Payment with id \"$id\" not found")
        return populate(row)
    }
}

/** The original `OpenOrdersService`: the order context stored per payment. */
@Service
class OpenOrdersService(private val store: PaymentStore) {
    suspend fun create(paymentId: String, orderJson: String) = store.insertOpenOrder(paymentId, orderJson)

    suspend fun findOne(paymentId: String?): String =
        store.findOpenOrder(paymentId) ?: throw NotFoundException("Open order for payment: $paymentId not found")

    suspend fun delete(paymentId: String?): String =
        store.deleteOpenOrder(paymentId) ?: throw NotFoundException("Open order for payment: $paymentId not found")
}
