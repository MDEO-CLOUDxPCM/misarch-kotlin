package org.misarch.payment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLDefaultValue
import org.misarch.payment.graphql.ConnectionArgs
import org.misarch.payment.graphql.input.PaymentFilter
import org.misarch.payment.graphql.input.PaymentInformationFilter
import org.misarch.payment.graphql.input.PaymentInformationOrder
import org.misarch.payment.graphql.input.PaymentOrder
import org.misarch.payment.nest.JsDate
import org.misarch.common.nest.JsError
import org.misarch.payment.nest.JsFloat
import org.misarch.payment.nest.JsonObjectValue
import org.misarch.common.nest.RolesGuard
import org.misarch.common.nest.Uuid
import org.misarch.payment.persistence.Match
import org.misarch.payment.persistence.PaymentInformationRow
import org.misarch.payment.service.PaymentInformationService
import org.misarch.payment.service.PaymentService
import org.misarch.payment.service.PopulatedPayment
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@GraphQLDescription("The supported payment methods")
enum class PaymentMethod {
    @GraphQLDescription("The user pays after ordering the product")
    INVOICE,

    @GraphQLDescription("The user pays before the product is shipped")
    PREPAYMENT,

    @GraphQLDescription("The amount is charged to the users credit card")
    CREDIT_CARD,
}

@GraphQLDescription("The status of an payment of an invoice or return")
enum class PaymentStatus {
    @GraphQLDescription("The payment was created but not yet processed")
    OPEN,

    @GraphQLDescription("The payment is currently being processed")
    PENDING,

    @GraphQLDescription("The payment was successfully processed and the amount was transfered")
    SUCCEEDED,

    @GraphQLDescription("The payment processing failed indefinetely")
    FAILED,

    @GraphQLDescription("The payment was sold to external inkasso service")
    INKASSO,
}

@GraphQLDescription("The direction to order a list of items by")
enum class OrderDirection {
    @GraphQLDescription("Ascending Order")
    ASC,

    @GraphQLDescription("Decending Order")
    DESC,
}

@GraphQLDescription("The field to order payment informations by")
enum class PaymentInformationOrderField {
    @GraphQLDescription("Order payment informations by their id")
    ID,
}

@GraphQLDescription("The field to order Payments by")
enum class PaymentOrderField {
    @GraphQLDescription("Order Payments by their ID")
    ID,
}

private val jsonMapper = JsonMapper.builder().build()

/**
 * Owner check of the original (after guard and validation, `req.user` = parsed header):
 * `!roles.some(isEmployeeOrAdmin) && currentUser.id.toString() !== ownerId.toString()` → UnauthorizedException.
 */
private fun requireOwnerOrEmployee(user: JsonNode, ownerId: () -> String, message: String) {
    val roles = if (user.isObject) user.get("roles") else null
    if (!PaymentInformationService.hasEmployeeRole(roles)) {
        val current = PaymentInformationService.jsToString(if (user.isObject) user.get("id") else null)
        if (current != ownerId()) throw org.misarch.common.nest.UnauthorizedException(message)
    }
}

@GraphQLDescription("Foreign type User")
@KeyDirective(fields = FieldSet("id"))
class User(internal val rawId: String?) {

    @GraphQLDescription("The uuid identifier of the user")
    fun id(): Uuid = rawId?.let { Uuid(it) } ?: throw JsError("Cannot return null for non-nullable field User.id.")

    @GraphQLDescription("A connection for an users payment informations.")
    suspend fun paymentInformations(
        @GraphQLDescription("Number of items to skip")
        @GraphQLDefaultValue("0")
        skip: Int? = 0,
        @GraphQLDescription("Number of items to return")
        @GraphQLDefaultValue("2147483647")
        first: Int? = Int.MAX_VALUE,
        @GraphQLDescription("Ordering")
        orderBy: PaymentInformationOrder? = null,
        @GraphQLDescription("Filtering")
        filter: PaymentInformationFilter? = null,
        @GraphQLIgnore @Autowired paymentInformationService: PaymentInformationService,
        dfe: DataFetchingEnvironment,
    ): PaymentInformationConnection? {
        val user = RolesGuard.check(dfe, RolesGuard.BUYER, RolesGuard.ADMIN, RolesGuard.EMPLOYEE)
        val args = ConnectionArgs(dfe)
        args.validate()
        requireOwnerOrEmployee(user, {
            rawId ?: throw JsError("Cannot read properties of undefined (reading 'toString')")
        }, "User not authorized to access foreign Payment Informations}")
        // filter = {...args.filter, user: {id: user.id}}: matches the stored {id} sub-document
        val userMatch = if (rawId == null) Match.Null else Match.Value(rawId)
        val method = args.filterMatch("paymentMethod")
        return PaymentInformationConnection.build(
            args,
            nodes = { paymentInformationService.find(userMatch, method, args.orderBy, args.first, args.skip) },
            count = { paymentInformationService.count(userMatch, method) },
        )
    }
}

/** The source of a `PaymentInformation` object: a stored document. */
sealed interface InformationSource {
    data class Document(val row: PaymentInformationRow) : InformationSource
}

@GraphQLDescription("A stored payment information of an user")
@KeyDirective(fields = FieldSet("id"))
class PaymentInformation(internal val source: InformationSource) {

    private val row: PaymentInformationRow? get() = (source as? InformationSource.Document)?.row

    @GraphQLDescription("The uuid identifier of the payment information")
    fun id(): Uuid = row?.let { Uuid(it.id) }
        ?: throw JsError("Cannot return null for non-nullable field PaymentInformation.id.")

    @GraphQLDescription("The corresponding payment method")
    fun paymentMethod(): PaymentMethod = row?.let { PaymentMethod.valueOf(it.paymentMethod) }
        ?: throw JsError("Cannot return null for non-nullable field PaymentInformation.paymentMethod.")

    @GraphQLDescription("The details for the User to identify the payment method")
    fun publicMethodDetails(): JsonObjectValue? = row?.publicMethodDetails?.let {
        @Suppress("UNCHECKED_CAST")
        JsonObjectValue(jsonMapper.readValue(it, LinkedHashMap::class.java) as Map<String, Any?>)
    }

    @GraphQLDescription("The user who owns the payment information")
    fun user(): User {
        val row = row ?: throw JsError("Cannot read properties of undefined (reading 'id')")
        return User(row.userId)
    }

    @GraphQLDescription("A connection for an users payments made with a payment information.")
    suspend fun payments(
        @GraphQLDescription("Number of items to skip")
        @GraphQLDefaultValue("0")
        skip: Int? = 0,
        @GraphQLDescription("Number of items to return")
        @GraphQLDefaultValue("2147483647")
        first: Int? = Int.MAX_VALUE,
        @GraphQLDescription("Ordering")
        orderBy: PaymentOrder? = null,
        @GraphQLDescription("Filtering")
        filter: PaymentFilter? = null,
        @GraphQLIgnore @Autowired paymentService: PaymentService,
        dfe: DataFetchingEnvironment,
    ): PaymentConnection? {
        val user = RolesGuard.check(dfe, RolesGuard.BUYER, RolesGuard.ADMIN, RolesGuard.EMPLOYEE)
        val args = ConnectionArgs(dfe)
        args.validate()
        requireOwnerOrEmployee(user, {
            val owner = row ?: throw JsError("Cannot read properties of undefined (reading 'id')")
            owner.userId ?: throw JsError("Cannot read properties of undefined (reading 'toString')")
        }, "User not authorized to access foreign Payments}")
        // filter = {...args.filter, paymentInformationId: paymentInformation.id}
        val filter = LinkedHashMap(args.filter ?: emptyMap()).apply { put("paymentInformationId", row?.id) }
        return PaymentConnection.build(
            args,
            nodes = { paymentService.find(filter, args.orderBy, args.first, args.skip) },
            count = { paymentService.count(filter) },
        )
    }
}

@GraphQLDescription("A payment of an invoice or return")
@KeyDirective(fields = FieldSet("id"))
class Payment(internal val payment: PopulatedPayment) {

    @GraphQLDescription("The uuid identifier of the payment")
    fun id(): Uuid = Uuid(payment.row.id)

    @GraphQLDescription("Payment Amount in the smallest currency unit (e.g. cents)")
    fun totalAmount(): JsFloat = JsFloat(payment.row.totalAmount)

    @GraphQLDescription("Status of the payment")
    fun status(): PaymentStatus = PaymentStatus.valueOf(payment.row.status)

    @GraphQLDescription("Used Payment Information")
    fun paymentInformation(): PaymentInformation = when {
        payment.info == null -> throw JsError("Cannot return null for non-nullable field Payment.paymentInformation.")
        else -> PaymentInformation(InformationSource.Document(payment.info))
    }

    @GraphQLDescription("Date of the payment")
    fun payedAt(): JsDate? = payment.row.payedAt?.let { JsDate(it) }

    /** Credit-card retries made so far (fix P2; NULL in rows of the original port = 0). */
    @GraphQLDescription("Number of retries for the payment process")
    fun numberOfRetries(): JsFloat = JsFloat(payment.row.numberOfRetries ?: 0.0)
}

/** Connections built like the original's `buildConnection` (payment variant of `queryKeys`). */
@GraphQLDescription("A connection of payments")
class PaymentConnection private constructor(
    private val nodeList: List<Payment>?,
    private val total: Long?,
    private val nextPage: Boolean?,
) {
    @GraphQLDescription("The resulting items")
    fun nodes(): List<Payment>? = nodeList

    @GraphQLDescription("The total amount of items in this connection")
    fun totalCount(): Int = total?.toInt()
        ?: throw JsError("Cannot return null for non-nullable field PaymentConnection.totalCount.")

    @GraphQLDescription("Whether this connection has a next page")
    fun hasNextPage(): Boolean = nextPage
        ?: throw JsError("Cannot return null for non-nullable field PaymentConnection.hasNextPage.")

    companion object {
        suspend fun build(args: ConnectionArgs, nodes: suspend () -> List<PopulatedPayment>, count: suspend () -> Long): PaymentConnection {
            val keys = args.keys
            val nodeList = if ("nodes" in keys) nodes().map { Payment(it) } else null
            var total: Long? = null
            var nextPage: Boolean? = null
            if ("totalCount" in keys || "hasNextPage" in keys) {
                total = count()
                nextPage = (args.skip ?: 0) + (args.first ?: 0) < total
            }
            return PaymentConnection(nodeList, total, nextPage)
        }
    }
}

@GraphQLDescription("A connection of payment informations")
class PaymentInformationConnection private constructor(
    private val nodeList: List<PaymentInformation>?,
    private val total: Long?,
    private val nextPage: Boolean?,
) {
    @GraphQLDescription("The resulting items")
    fun nodes(): List<PaymentInformation>? = nodeList

    @GraphQLDescription("The total amount of items in this connection")
    fun totalCount(): Int = total?.toInt()
        ?: throw JsError("Cannot return null for non-nullable field PaymentInformationConnection.totalCount.")

    @GraphQLDescription("Whether this connection has a next page")
    fun hasNextPage(): Boolean = nextPage
        ?: throw JsError("Cannot return null for non-nullable field PaymentInformationConnection.hasNextPage.")

    companion object {
        suspend fun build(
            args: ConnectionArgs,
            nodes: suspend () -> List<PaymentInformationRow>,
            count: suspend () -> Long,
        ): PaymentInformationConnection {
            val keys = args.keys
            val nodeList = if ("nodes" in keys) nodes().map { PaymentInformation(InformationSource.Document(it)) } else null
            var total: Long? = null
            var nextPage: Boolean? = null
            if ("totalCount" in keys || "hasNextPage" in keys) {
                total = count()
                nextPage = (args.skip ?: 0) + (args.first ?: 0) < total
            }
            return PaymentInformationConnection(nodeList, total, nextPage)
        }
    }
}
