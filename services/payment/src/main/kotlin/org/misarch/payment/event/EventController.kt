package org.misarch.payment.event

import org.misarch.common.nest.ClassValidator
import org.misarch.common.nest.ClassValidator.Property
import org.misarch.common.nest.ClassValidator.Schema
import org.misarch.common.nest.DaprSubscriptions
import org.misarch.common.nest.DetachedTasks
import org.misarch.common.nest.Js
import org.misarch.common.nest.NestRest
import org.misarch.payment.provider.PaymentEvents
import org.misarch.payment.provider.PaymentProcess
import org.misarch.payment.service.PaymentInformationService
import org.misarch.payment.service.PopulatedPayment
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class PaymentConfiguration {
    @Bean
    fun daprSubscriptions() = DaprSubscriptions(PaymentEvents.SUBSCRIPTIONS)

    /** The server clock (the original uses `new Date()` in the container's time zone). */
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}

/** The class-validator decorators of the original payment DTOs. */
object PaymentDtos {
    /** `ValidationSucceededDTO`: `@IsObject() order` (the nested order is not validated). */
    val VALIDATION_SUCCEEDED = Schema(listOf(Property("order", listOf(ClassValidator.isObject()))))

    /** `UserCreatedDto` */
    val USER_CREATED = Schema(
        listOf(
            Property("id", listOf(ClassValidator.isUUID())),
            Property("username", listOf(ClassValidator.isString())),
            Property("firstName", listOf(ClassValidator.isString())),
            Property("lastName", listOf(ClassValidator.isString())),
            Property("dateJoined", listOf(ClassValidator.isString())),
        )
    )

    /** `UpdatePaymentStatusDto` (provider callback) */
    val UPDATE_PAYMENT_STATUS = Schema(
        listOf(
            Property("paymentId", listOf(ClassValidator.isString())),
            Property("status", listOf(ClassValidator.isEnum(listOf("OPEN", "PENDING", "SUCCEEDED", "FAILED", "INKASSO")))),
        )
    )
}

/**
 * Dapr subscription routes and the provider callback of the original (docs/specs/payment.md §5, §6.2).
 */
@RestController
class EventController(
    private val paymentInformationService: PaymentInformationService,
    private val paymentProcess: PaymentProcess,
    private val detached: DetachedTasks,
) {
    private val logger = LoggerFactory.getLogger(EventController::class.java)
    private val mapper = JsonMapper.builder().build()

    @PostMapping("/order-validation-succeeded")
    suspend fun orderValidationSucceeded(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val event = NestRest.parseBody(body).get("data")
        ClassValidator.validateOrThrow(PaymentDtos.VALIDATION_SUCCEEDED, event)
        val order = event!!.get("order")
        logger.info("Received discount order validation success event for order \"{}\"", order.get("id"))
        detached.launch("startPaymentProcess") { paymentProcess.startPaymentProcess(order) }
        NestRest.created()
    }

    @PostMapping("/user-created")
    suspend fun userCreated(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val user = NestRest.parseBody(body).get("data")
        ClassValidator.validateOrThrow(PaymentDtos.USER_CREATED, user)
        val id = user!!.get("id").asString()
        logger.info("Received user creation event: {}", user)
        detached.launch("addDefaultPaymentInformations") { paymentInformationService.addDefaultPaymentInformations(id) }
        NestRest.created()
    }

    /** Provider callback (`PaymentProviderConnectionController`), plain HTTP from the simulation. */
    @PostMapping("/payment/update-payment-status")
    suspend fun updatePaymentStatus(@RequestBody(required = false) body: String?): ResponseEntity<String> = NestRest.handle {
        val dto = NestRest.parseBody(body)
        ClassValidator.validateOrThrow(PaymentDtos.UPDATE_PAYMENT_STATUS, dto)
        logger.info("{updatePaymentStatus} Updating payment status with dto: {}", dto)
        val result = paymentProcess.updatePaymentStatus(dto.get("paymentId").asString(), dto.get("status").asString())
        if (result == null) NestRest.created() else NestRest.created(documentJson(result))
    }

    /** The populated payment document as Mongoose serializes it (`toJSON` with virtuals). */
    private fun documentJson(payment: PopulatedPayment): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        val row = payment.row
        put("_id", row.id)
        put("totalAmount", jsNumber(row.totalAmount))
        put("status", row.status)
        put("paymentInformation", payment.info?.let { info ->
            linkedMapOf<String, Any?>().apply {
                put("_id", info.id)
                put("paymentMethod", info.paymentMethod)
                info.publicMethodDetails?.let { put("publicMethodDetails", mapper.readTree(it)) }
                info.secretMethodDetails?.let { put("secretMethodDetails", mapper.readTree(it)) }
                put("user", linkedMapOf("id" to info.userId))
                put("id", info.id)
            }
        })
        put("createdAt", Js.toIsoString(row.createdAt))
        put("updatedAt", Js.toIsoString(row.updatedAt))
        row.payedAt?.let { put("payedAt", Js.toIsoString(it)) }
        put("id", row.id)
    }

    private fun jsNumber(value: Double): Any = if (value == Math.floor(value) && Math.abs(value) < 9.007199254740992E15) value.toLong() else value
}
