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
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
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
 * `sort({[orderBy.field]: orderBy.direction})` with defaults (fix P8, docs/fixes/payment.md): `orderBy`, its `field`
 * (only `ID` exists) and its `direction` default to `ID` / `ASC`.
 */
fun sortOf(orderBy: Map<String, Any?>?): Sort {
    val direction = orderBy?.get("direction")?.let { if (it is Enum<*>) it.name else it.toString() }
    return if (direction == "DESC") Sort.ID_DESC else Sort.ID_ASC
}

/** Masks every digit of a card number except the last four (fix P10); separators are kept. */
fun maskCardNumber(cardNumber: String): String {
    val digits = cardNumber.count { it in '0'..'9' }
    var seen = 0
    return buildString(cardNumber.length) {
        for (c in cardNumber) {
            if (c in '0'..'9') {
                seen++
                append(if (seen <= digits - 4) '*' else c)
            } else {
                append(c)
            }
        }
    }
}

/** The original `PaymentInformationService` (docs/specs/payment.md §4.2). */
@Service
class PaymentInformationService(private val store: PaymentStore, transactionManager: ReactiveTransactionManager) {
    private val logger = LoggerFactory.getLogger(PaymentInformationService::class.java)
    private val mapper = JsonMapper.builder().build()
    private val transaction = TransactionalOperator.create(transactionManager)

    /** `createCreditCardInformation(input, {id: currentUser.id})` */
    suspend fun createCreditCardInformation(input: Map<String, Any?>, userId: String?): PaymentInformationRow {
        val cardNumber = input["cardNumber"] as String
        val publicDetails = linkedMapOf(
            "cardHolder" to input["cardHolder"],
            "cardNumber" to maskCardNumber(cardNumber),
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

    /**
     * Creates the default payment informations (prepayment, then invoice) of a new user. Idempotent (fix P4): a
     * method the user already has is not created again; concurrent deliveries for one user are serialized.
     */
    suspend fun addDefaultPaymentInformations(userId: String) {
        logger.info("{addDefaultPaymentInformations} for user: {}", userId)
        transaction.executeAndAwait {
            store.lockUser(userId)
            for (method in listOf(PaymentMethods.PREPAYMENT, PaymentMethods.INVOICE)) {
                if (store.informationExists(userId, method)) {
                    logger.info("{addDefaultPaymentInformations} user {} already has a {} payment information", userId, method)
                } else {
                    createPaymentInformation(method, userId)
                }
            }
        }
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
     * `delete(_id, user, roles)`: admins and employees may delete any payment information, buyers their own (fix P7:
     * the owner id is compared, not the stored user object).
     */
    suspend fun delete(id: String, currentUserId: JsonNode?, roles: JsonNode?): PaymentInformationRow {
        val info = store.findInformation(id) ?: throw NotFoundException("Payment Information with id \"$id\" not found")
        if (!hasEmployeeRole(roles) && info.userId != jsToString(currentUserId)) {
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
    transactionManager: ReactiveTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)
    private val transaction = TransactionalOperator.create(transactionManager)

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

    /** `delete`: the deleted payment is returned populated (fix P9). */
    suspend fun delete(id: String): PopulatedPayment {
        val payment = store.findPayment(id)?.let { populate(it) } ?: throw NotFoundException("Payment with id \"$id\" not found")
        val deleted = store.deletePayment(id) ?: throw JsError("Cannot return null for non-nullable field Mutation.deletePayment.")
        return payment.copy(row = deleted)
    }

    /**
     * `create(order)` + `openOrdersService.create(payment.id, order)`: looks up the payment information and stores an
     * OPEN payment together with its open order, atomically (fix P1). Returns null if a payment for this order id
     * already exists (redelivered event, fix P4); nothing is stored then.
     */
    suspend fun create(order: JsonNode, orderJson: String): Pair<PaymentRow, PaymentInformationRow>? {
        val paymentInformationId = Js.toJsString(order.get("paymentInformationId"))
        logger.info("{create} Creating payment for order \"{}\" with paymentInformationId \"{}\"", order.get("id"), paymentInformationId)
        val info = paymentInformationService.findById(paymentInformationId)
        val totalAmount = MongooseCast.number(order.get("compensatableOrderAmount"), "totalAmount", "Payment")
        val orderId = order.get("id")?.takeUnless { it.isNull || it.isMissingNode }?.let { Js.toJsString(it) }
        val now = Js.now()
        val row = PaymentRow(UUID.randomUUID().toString(), totalAmount, PaymentStatuses.OPEN, info.id, null, 0.0, now, now)
        val created = transaction.executeAndAwait {
            // the open order first: its unique order id decides whether this order already has a payment
            if (!store.insertOpenOrder(row.id, orderId, orderJson)) {
                false
            } else {
                store.insertPayment(row)
                true
            }
        }
        if (created != true) {
            logger.info("{create} Order \"{}\" already has a payment, event ignored", orderId)
            return null
        }
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

    /**
     * Like [updatePaymentStatus], but only if the status changes: returns null if the payment already has [status]
     * (so that the follow-up event of a transition is published once).
     */
    suspend fun transitionPaymentStatus(id: String, status: String): PopulatedPayment? {
        logger.info("{transitionPaymentStatus} Updating payment status for id: \"{}\" to {}", id, status)
        val now = Js.now()
        val row = store.updateStatus(id, status, if (status == PaymentStatuses.SUCCEEDED) now else null, now, onlyIfChanged = true)
        if (row == null) {
            store.findPayment(id) ?: throw NotFoundException("Payment with id \"$id\" not found")
            return null
        }
        return populate(row)
    }

    /** Counts a credit-card retry; null if [limit] retries were already made. */
    suspend fun countRetry(id: String, limit: Int): PaymentRow? = store.incrementRetries(id, limit, Js.now())
}

/** The original `OpenOrdersService`: the order context stored per payment (created by [PaymentService.create]). */
@Service
class OpenOrdersService(private val store: PaymentStore) {
    suspend fun findOne(paymentId: String?): String =
        store.findOpenOrder(paymentId) ?: throw NotFoundException("Open order for payment: $paymentId not found")

    suspend fun delete(paymentId: String?): String =
        store.deleteOpenOrder(paymentId) ?: throw NotFoundException("Open order for payment: $paymentId not found")
}
