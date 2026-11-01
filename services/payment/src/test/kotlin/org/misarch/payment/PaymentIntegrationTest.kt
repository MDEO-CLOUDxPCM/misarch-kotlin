package org.misarch.payment

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.misarch.common.testing.FakeDaprSidecar
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.testing.SqlStatementLog
import org.misarch.payment.service.PaymentService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Behaviour of the payment service against a real Postgres (Testcontainers), a fake Dapr sidecar and a fake payment
 * provider.
 */
@Import(SqlStatementLog::class)
class PaymentIntegrationTest : MisarchIntegrationTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun providerProperties(registry: DynamicPropertyRegistry) {
            registry.add("misarch.payment.provider.simulation-url") { FakePaymentProvider.url }
        }
    }

    @Autowired
    lateinit var paymentService: PaymentService

    @Autowired
    lateinit var db: DatabaseClient

    private val provider = FakePaymentProvider
    private val employeeId = UUID.randomUUID()
    private val employee = authorizedUserHeader(employeeId, "employee")
    private val admin = authorizedUserHeader(UUID.randomUUID(), "admin")

    @BeforeEach
    fun resetProvider() = provider.reset()

    // ---------------------------------------------------------------- helpers

    private fun buyer(id: UUID) = authorizedUserHeader(id, "buyer")

    private fun userCreated(id: UUID): Int = deliverEvent(
        "/subscription/user/user/created", "user/user/created",
        """{"id":"$id","username":"u","firstName":"f","lastName":"l","dateJoined":"2026-01-01T00:00:00Z"}"""
    )

    /** Registers a user and returns the ids of its payment informations by method. */
    private fun newUser(): Pair<UUID, Map<String, String>> {
        val id = UUID.randomUUID()
        assertThat(userCreated(id)).isEqualTo(200)
        return id to informationsOf(id)
    }

    private fun informationsOf(userId: UUID): Map<String, String> = graphql(
        """{ _entities(representations: [{__typename: "User", id: "$userId"}]) { ... on User { paymentInformations { nodes { id paymentMethod } } } } }""",
        user = buyer(userId)
    )["data"]["_entities"][0]["paymentInformations"]["nodes"].values()
        .associate { it["paymentMethod"].asString() to it["id"].asString() }

    private fun orderJson(paymentInformationId: String, orderId: UUID = UUID.randomUUID(), amount: String = "1999") =
        """{"id":"$orderId","userId":"${UUID.randomUUID()}","createdAt":"2026-10-13T12:00:00.123Z","orderStatus":"PLACED","placedAt":"2026-10-13T12:00:01.000Z","rejectionReason":null,"orderItems":[{"id":"${UUID.randomUUID()}","count":1,"compensatableAmount":1999,"discountIds":[]}],"shipmentAddressId":"${UUID.randomUUID()}","invoiceAddressId":"${UUID.randomUUID()}","compensatableOrderAmount":$amount,"paymentInformationId":"$paymentInformationId","paymentAuthorization":null,"vatNumber":null}"""

    private fun validationSucceeded(order: String): Int = deliverEvent(
        "/subscription/discount/order/validation-succeeded", "discount/order/validation-succeeded", """{"order":$order}"""
    )

    private fun paymentsOf(paymentInformationId: String): List<JsonNode> = graphql(
        """{ payments(filter: {paymentInformationId: "$paymentInformationId"}) { nodes { id totalAmount status payedAt numberOfRetries paymentInformation { id paymentMethod } } } }""",
        user = employee
    )["data"]["payments"]["nodes"].values().toList()

    /** Creates a payment for a new order; returns the order, its JSON and the payment id. */
    private fun startPayment(paymentInformationId: String): Triple<UUID, String, UUID> {
        val before = paymentsOf(paymentInformationId).map { it["id"].asString() }.toSet()
        val orderId = UUID.randomUUID()
        val order = orderJson(paymentInformationId, orderId)
        assertThat(validationSucceeded(order)).isEqualTo(200)
        val payment = paymentsOf(paymentInformationId).single { it["id"].asString() !in before }
        assertThat(payment["status"].asString()).isEqualTo("PENDING")
        return Triple(orderId, order, UUID.fromString(payment["id"].asString()))
    }

    private fun updateStatus(paymentId: UUID, status: String): Int = webTestClient.post().uri("/payment/update-payment-status")
        .header("Content-Type", "application/json")
        .bodyValue("""{"paymentId":"$paymentId","status":"$status"}""")
        .exchange()
        .returnResult(String::class.java)
        .status.value()

    private fun newCard(userId: UUID): String = graphql(
        """mutation { createCreditCardPaymentInformation(input: {cardHolder: "A", cardNumber: "4111111111111111", expirationDate: "12/40"}) { id } }""",
        user = buyer(userId)
    )["data"]["createCreditCardPaymentInformation"]["id"].asString()

    private fun events(topic: String, orderId: UUID): List<FakeDaprSidecar.PublishedEvent> =
        dapr.published(topic).filter { it.json["order"]["id"].asString() == orderId.toString() }

    private fun registrations(paymentId: UUID) = provider.requests.filter { it.body.contains(paymentId.toString()) }

    private fun makeOverdue(paymentId: UUID, days: Long) = runBlocking {
        db.sql("UPDATE PaymentEntity SET createdAt = now() - make_interval(days => :days) WHERE id = :id")
            .bind("days", days.toInt()).bind("id", paymentId).fetch().awaitRowsUpdated()
    }

    private fun errorMessage(result: JsonNode): String = result["errors"][0]["message"].asString()

    // ---------------------------------------------------------------- payment informations

    @Test
    fun `user created adds prepayment and invoice once, also when redelivered concurrently`() {
        val (id, infos) = newUser()
        assertThat(infos.keys).containsExactlyInAnyOrder("PREPAYMENT", "INVOICE")
        assertThat(userCreated(id)).isEqualTo(200)
        assertThat(informationsOf(id)).isEqualTo(infos)

        val concurrent = UUID.randomUUID()
        (1..4).map { Thread { userCreated(concurrent) }.apply { start() } }.forEach { it.join() }
        assertThat(informationsOf(concurrent).keys).containsExactlyInAnyOrder("PREPAYMENT", "INVOICE")

        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"nope"}""")).isEqualTo(400)
    }

    @Test
    fun `credit card informations are validated, masked and only created by buyers`() {
        val (userId, _) = newUser()
        val created = graphql(
            """mutation { createCreditCardPaymentInformation(input: {cardHolder: "A B", cardNumber: "4111 1111 1111 1111", expirationDate: "12/40"}) { id paymentMethod publicMethodDetails user { id } } }""",
            user = buyer(userId)
        )["data"]["createCreditCardPaymentInformation"]
        assertThat(created["paymentMethod"].asString()).isEqualTo("CREDIT_CARD")
        assertThat(created["publicMethodDetails"].toString())
            .isEqualTo("""{"cardHolder":"A B","cardNumber":"**** **** **** 1111","expirationDate":"12/40"}""")
        assertThat(created["user"]["id"].asString()).isEqualTo(userId.toString())

        val invalid = graphql(
            """mutation { createCreditCardPaymentInformation(input: {cardHolder: "A", cardNumber: "1234", expirationDate: "01/20"}) { id } }""",
            user = buyer(userId)
        )
        assertThat(errorMessage(invalid)).isEqualTo(
            "Exception while fetching data (/createCreditCardPaymentInformation) : The credit card number is invalid, The expiration date is invalid or expired"
        )
        val asEmployee = graphql(
            """mutation { createCreditCardPaymentInformation(input: {cardHolder: "A", cardNumber: "4111111111111111", expirationDate: "12/40"}) { id } }""",
            user = employee
        )
        assertThat(errorMessage(asEmployee)).endsWith("Unauthorized access: $employeeId is not a buyer")
    }

    @Test
    fun `payment informations are visible to their owner and employees, filtered and ordered`() {
        val (userId, infos) = newUser()
        val other = UUID.randomUUID()
        val foreign = graphql(
            """{ _entities(representations: [{__typename: "User", id: "$userId"}]) { ... on User { paymentInformations { totalCount } } } }""",
            user = buyer(other)
        )
        assertThat(foreign["data"]["_entities"][0]["paymentInformations"].isNull).isTrue()
        assertThat(errorMessage(foreign)).endsWith("Unauthorized access: $other is not an employee or admin")

        val filtered = graphql(
            """{ _entities(representations: [{__typename: "User", id: "$userId"}]) { ... on User { paymentInformations(filter: {paymentMethod: INVOICE}) { nodes { id } totalCount } } } }""",
            user = employee
        )["data"]["_entities"][0]["paymentInformations"]
        assertThat(filtered["nodes"].values().map { it["id"].asString() }).containsExactly(infos["INVOICE"])
        assertThat(filtered["totalCount"].asInt()).isEqualTo(1)

        val byUser = graphql("""{ paymentInformations(filter: {user: "$userId"}) { nodes { id } totalCount hasNextPage } }""", user = employee)["data"]["paymentInformations"]
        assertThat(byUser["nodes"].values().map { it["id"].asString() }).isEqualTo(infos.values.sorted())
        assertThat(byUser["totalCount"].asInt()).isEqualTo(2)
        assertThat(byUser["hasNextPage"].asBoolean()).isFalse()
        val descending = graphql("""{ paymentInformations(filter: {user: "$userId"}, orderBy: {direction: DESC}) { nodes { id } } }""", user = employee)
        assertThat(descending["data"]["paymentInformations"]["nodes"].values().map { it["id"].asString() }).isEqualTo(infos.values.sortedDescending())
        val page = graphql("""{ paymentInformations(filter: {user: "$userId"}, first: 1) { nodes { id } hasNextPage } }""", user = employee)
        assertThat(page["data"]["paymentInformations"]["hasNextPage"].asBoolean()).isTrue()

        assertThat(errorMessage(graphql("""{ paymentInformations { totalCount } }""", user = buyer(userId)))).endsWith("is not an employee or admin")
    }

    @Test
    fun `buyers delete their own payment informations, employees any`() {
        val (userId, infos) = newUser()
        val other = UUID.randomUUID()
        val foreign = graphql("""mutation { deletePaymentInformation(id: "${infos["INVOICE"]}") { id } }""", user = buyer(other))
        assertThat(errorMessage(foreign)).endsWith("Unauthorized access: $other is not an employee or admin")
        val own = graphql("""mutation { deletePaymentInformation(id: "${infos["PREPAYMENT"]}") { id paymentMethod } }""", user = buyer(userId))
        assertThat(own["data"]["deletePaymentInformation"]["paymentMethod"].asString()).isEqualTo("PREPAYMENT")
        assertThat(informationsOf(userId).keys).containsExactly("INVOICE")
        val missing = UUID.randomUUID()
        assertThat(errorMessage(graphql("""mutation { deletePaymentInformation(id: "$missing") { id } }""", user = employee)))
            .isEqualTo("Exception while fetching data (/deletePaymentInformation) : Payment Information with id \"$missing\" not found")
        val deleted = graphql("""mutation { deletePaymentInformation(id: "${infos["INVOICE"]}") { paymentMethod } }""", user = employee)
        assertThat(deleted["data"]["deletePaymentInformation"]["paymentMethod"].asString()).isEqualTo("INVOICE")
    }

    // ---------------------------------------------------------------- payment process

    @Test
    fun `an invoice payment is registered, enables the order and succeeds by callback`() {
        val (_, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val (orderId, order, paymentId) = startPayment(infoId)
        val enabled = events("payment/payment/payment-enabled", orderId)
        assertThat(enabled).hasSize(1)
        assertThat(enabled[0].json["order"]).isEqualTo(mapper.readTree(order))
        assertThat(enabled[0].body).contains("\"rejectionReason\":null").contains("\"paymentAuthorization\":null")
        val register = registrations(paymentId).single()
        assertThat(register.path).isEqualTo("/payment/register")
        assertThat(register.body).isEqualTo("""{"paymentId":"$paymentId","amount":1999.0,"paymentType":"invoice"}""")
        assertThat(register.headers["content-type"]).startsWith("application/json")

        val payment = paymentsOf(infoId).single()
        assertThat(payment["totalAmount"].asDouble()).isEqualTo(1999.0)
        assertThat(payment["numberOfRetries"].asDouble()).isEqualTo(0.0)
        assertThat(payment["payedAt"].isNull).isTrue()

        assertThat(updateStatus(paymentId, "SUCCEEDED")).isEqualTo(200)
        val succeeded = paymentsOf(infoId).single()
        assertThat(succeeded["status"].asString()).isEqualTo("SUCCEEDED")
        assertThat(succeeded["payedAt"].asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z")
    }

    @Test
    fun `failed credit card payments are retried three times, then fail and publish payment-failed once`() {
        val (userId, _) = newUser()
        val cardId = newCard(userId)
        val (orderId, order, paymentId) = startPayment(cardId)
        assertThat(events("payment/payment/payment-enabled", orderId)).hasSize(1)
        for (retry in 1..3) {
            assertThat(updateStatus(paymentId, "FAILED")).isEqualTo(200)
            val payment = paymentsOf(cardId).single()
            assertThat(payment["status"].asString()).isEqualTo("PENDING")
            assertThat(payment["numberOfRetries"].asInt()).isEqualTo(retry)
        }
        assertThat(registrations(paymentId).map { it.body }).hasSize(4).containsOnly(
            """{"paymentId":"$paymentId","amount":1999.0,"paymentType":"credit-card"}"""
        )
        assertThat(events("payment/payment/payment-failed", orderId)).isEmpty()

        assertThat(updateStatus(paymentId, "FAILED")).isEqualTo(200)
        assertThat(paymentsOf(cardId).single()["status"].asString()).isEqualTo("FAILED")
        val failed = events("payment/payment/payment-failed", orderId)
        assertThat(failed).hasSize(1)
        assertThat(failed[0].json["order"]).isEqualTo(mapper.readTree(order))
        // a further FAILED neither retries nor publishes again
        assertThat(updateStatus(paymentId, "FAILED")).isEqualTo(200)
        assertThat(events("payment/payment/payment-failed", orderId)).hasSize(1)
        assertThat(registrations(paymentId)).hasSize(4)
    }

    @Test
    fun `a credit card payment that succeeds on a retry is stored as succeeded`() {
        val (userId, _) = newUser()
        val cardId = newCard(userId)
        val (orderId, _, paymentId) = startPayment(cardId)
        updateStatus(paymentId, "FAILED")
        assertThat(updateStatus(paymentId, "SUCCEEDED")).isEqualTo(200)
        val payment = paymentsOf(cardId).single()
        assertThat(payment["status"].asString()).isEqualTo("SUCCEEDED")
        assertThat(payment["numberOfRetries"].asInt()).isEqualTo(1)
        assertThat(payment["payedAt"].isNull).isFalse()
        assertThat(events("payment/payment/payment-failed", orderId)).isEmpty()
    }

    @Test
    fun `a prepayment enables the order on success, once, and is not failed by the job afterwards`() {
        val (_, infos) = newUser()
        val infoId = infos.getValue("PREPAYMENT")
        val (orderId, _, paymentId) = startPayment(infoId)
        assertThat(events("payment/payment/payment-enabled", orderId)).isEmpty()
        assertThat(registrations(paymentId).single().body).contains("\"paymentType\":\"prepayment\"")
        assertThat(updateStatus(paymentId, "SUCCEEDED")).isEqualTo(200)
        assertThat(events("payment/payment/payment-enabled", orderId)).hasSize(1)
        assertThat(paymentsOf(infoId).single()["payedAt"].isNull).isFalse()
        assertThat(updateStatus(paymentId, "SUCCEEDED")).isEqualTo(200)
        assertThat(events("payment/payment/payment-enabled", orderId)).hasSize(1)

        makeOverdue(paymentId, 8)
        runBlocking { paymentService.failOverduePrepaymentPayments() }
        assertThat(events("payment/payment/payment-failed", orderId)).isEmpty()
        assertThat(paymentsOf(infoId).single()["status"].asString()).isEqualTo("SUCCEEDED")
    }

    @Test
    fun `a failed prepayment publishes payment-failed once, an unpaid one is failed by the job after 7 days`() {
        val (_, infos) = newUser()
        val infoId = infos.getValue("PREPAYMENT")
        val (orderId, _, paymentId) = startPayment(infoId)
        assertThat(updateStatus(paymentId, "FAILED")).isEqualTo(200)
        assertThat(updateStatus(paymentId, "FAILED")).isEqualTo(200)
        assertThat(events("payment/payment/payment-failed", orderId)).hasSize(1)
        assertThat(events("payment/payment/payment-enabled", orderId)).isEmpty()

        val (unpaidOrder, order, unpaid) = startPayment(infoId)
        makeOverdue(unpaid, 6)
        runBlocking { paymentService.failOverduePrepaymentPayments() }
        assertThat(events("payment/payment/payment-failed", unpaidOrder)).isEmpty()
        makeOverdue(unpaid, 8)
        runBlocking { paymentService.failOverduePrepaymentPayments() }
        val failed = events("payment/payment/payment-failed", unpaidOrder)
        assertThat(failed).hasSize(1)
        assertThat(failed[0].json["order"]).isEqualTo(mapper.readTree(order))
        assertThat(paymentsOf(infoId).single { it["id"].asString() == unpaid.toString() }["status"].asString()).isEqualTo("FAILED")
    }

    @Test
    fun `the invoice job fails pending payments older than 30 days with one query`() {
        val (_, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val (orderId, _, paymentId) = startPayment(infoId)
        makeOverdue(paymentId, 29)
        runBlocking { paymentService.failOverdueInvoicePayments() }
        assertThat(paymentsOf(infoId).single()["status"].asString()).isEqualTo("PENDING")
        makeOverdue(paymentId, 31)
        val statements = SqlStatementLog.record { runBlocking { paymentService.failOverdueInvoicePayments() } }
        assertThat(events("payment/payment/payment-failed", orderId)).hasSize(1)
        assertThat(paymentsOf(infoId).single()["status"].asString()).isEqualTo("FAILED")
        assertThat(statements.filter { "PaymentInformationEntity" in it }).singleElement().satisfies({
            assertThat(it).contains("INNER JOIN PaymentInformationEntity i ON i.id = t.paymentInformationId")
        })
    }

    @Test
    fun `an order without a valid payment information or amount publishes payment-failed and stores nothing`() {
        val orderId = UUID.randomUUID()
        val unknownInfo = UUID.randomUUID().toString()
        assertThat(validationSucceeded(orderJson(unknownInfo, orderId))).isEqualTo(200)
        assertThat(events("payment/payment/payment-failed", orderId).single().json["order"]["paymentInformationId"].asString())
            .isEqualTo(unknownInfo)

        val (_, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val invalidAmount = UUID.randomUUID()
        assertThat(validationSucceeded(orderJson(infoId, invalidAmount, amount = "\"abc\""))).isEqualTo(200)
        assertThat(events("payment/payment/payment-failed", invalidAmount)).hasSize(1)
        assertThat(paymentsOf(infoId)).isEmpty()
        assertThat(events("payment/payment/payment-enabled", invalidAmount)).isEmpty()
        assertThat(deliverEvent("/subscription/discount/order/validation-succeeded", "discount/order/validation-succeeded", "{}"))
            .isEqualTo(400)
    }

    @Test
    fun `a redelivered validation-succeeded event creates no second payment`() {
        val (_, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val (orderId, order, paymentId) = startPayment(infoId)
        assertThat(validationSucceeded(order)).isEqualTo(200)
        (1..4).map { Thread { validationSucceeded(order) }.apply { start() } }.forEach { it.join() }
        assertThat(paymentsOf(infoId).map { it["id"].asString() }).containsExactly(paymentId.toString())
        assertThat(events("payment/payment/payment-enabled", orderId)).hasSize(1)
        assertThat(registrations(paymentId)).hasSize(1)

        val newOrder = UUID.randomUUID()
        val newOrderJson = orderJson(infoId, newOrder)
        (1..4).map { Thread { validationSucceeded(newOrderJson) }.apply { start() } }.forEach { it.join() }
        assertThat(paymentsOf(infoId)).hasSize(2)
        assertThat(events("payment/payment/payment-enabled", newOrder)).hasSize(1)
    }

    @Test
    fun `a failing provider registration fails the event and stores nothing, the redelivery succeeds`() {
        val (_, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val orderId = UUID.randomUUID()
        val order = orderJson(infoId, orderId)
        provider.status = 500
        assertThat(validationSucceeded(order)).isEqualTo(500)
        assertThat(paymentsOf(infoId)).isEmpty()
        assertThat(events("payment/payment/payment-enabled", orderId)).isEmpty()
        provider.status = 201
        assertThat(validationSucceeded(order)).isEqualTo(200)
        assertThat(paymentsOf(infoId)).hasSize(1)
        assertThat(events("payment/payment/payment-enabled", orderId)).hasSize(1)
    }

    @Test
    fun `provider callbacks for unknown payments or invalid bodies fail`() {
        assertThat(updateStatus(UUID.randomUUID(), "SUCCEEDED")).isEqualTo(500)
        val invalid = webTestClient.post().uri("/payment/update-payment-status")
            .header("Content-Type", "application/json").bodyValue("""{"paymentId":5,"status":"DONE"}""")
            .exchange().returnResult(String::class.java).status.value()
        assertThat(invalid).isEqualTo(400)
    }

    @Test
    fun `Payment paymentInformation is loaded by its field, batched for the page`() {
        val (_, infos) = newUser()
        val invoice = infos.getValue("INVOICE")
        val prepayment = infos.getValue("PREPAYMENT")
        val first = startPayment(invoice).third
        val second = startPayment(invoice).third
        val third = startPayment(prepayment).third
        val ids = setOf(first, second, third).map { it.toString() }
        fun informationReads(statements: List<String>) = statements.filter { "paymentinformationentity" in it.lowercase() }

        var nodes: List<JsonNode> = emptyList()
        val withoutInformation = SqlStatementLog.record {
            nodes = graphql("{ payments { nodes { id status } } }", user = employee)["data"]["payments"]["nodes"].values().toList()
        }
        assertThat(nodes.map { it["id"].asString() }).containsAll(ids)
        assertThat(withoutInformation).hasSize(1)

        val withInformation = SqlStatementLog.record {
            nodes = graphql("{ payments { nodes { id paymentInformation { id paymentMethod } } } }", user = employee)["data"]["payments"]["nodes"].values().toList()
        }
        val byId = nodes.associate { it["id"].asString() to it["paymentInformation"] }
        assertThat(byId.getValue(first.toString())["id"].asString()).isEqualTo(invoice)
        assertThat(byId.getValue(third.toString())["paymentMethod"].asString()).isEqualTo("PREPAYMENT")
        assertThat(withInformation).hasSize(2)
        assertThat(informationReads(withInformation)).hasSize(1)
        assertThat(informationReads(withoutInformation)).isEmpty()
    }

    @Test
    fun `payments are filtered, resolved as entities and deleted by admins`() {
        val (userId, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val (_, _, paymentId) = startPayment(infoId)
        fun count(filter: String) = graphql("""{ payments(filter: $filter) { totalCount } }""", user = employee)["data"]["payments"]["totalCount"].asInt()
        assertThat(count("""{paymentInformationId: "$infoId"}""")).isEqualTo(1)
        assertThat(count("""{paymentInformationId: "$infoId", paymentMethod: PREPAYMENT}""")).isEqualTo(0)
        assertThat(count("""{paymentInformationId: "$infoId", paymentMethod: INVOICE, status: PENDING}""")).isEqualTo(1)
        assertThat(count("""{paymentInformationId: "$infoId", from: "2020-01-01T00:00:00Z", to: "2099-01-01T00:00:00+02:00"}""")).isEqualTo(1)
        assertThat(count("""{paymentInformationId: "$infoId", from: "2099-01-01T00:00:00Z"}""")).isEqualTo(0)
        assertThat(count("""{paymentInformationId: "not-a-uuid"}""")).isEqualTo(0)

        val own = graphql(
            """{ _entities(representations: [{__typename: "PaymentInformation", id: "$infoId"}]) { ... on PaymentInformation { payments { nodes { id } totalCount } } } }""",
            user = buyer(userId)
        )["data"]["_entities"][0]["payments"]
        assertThat(own["totalCount"].asInt()).isEqualTo(1)
        val foreign = graphql(
            """{ _entities(representations: [{__typename: "PaymentInformation", id: "$infoId"}]) { ... on PaymentInformation { payments { totalCount } } } }""",
            user = buyer(UUID.randomUUID())
        )
        assertThat(errorMessage(foreign)).contains("is not an employee or admin")

        val entities = graphql("""{ _entities(representations: [{__typename: "Payment", id: "$paymentId"}]) { ... on Payment { id status } } }""")
        assertThat(entities["data"]["_entities"][0]["id"].asString()).isEqualTo(paymentId.toString())

        assertThat(errorMessage(graphql("""mutation { deletePayment(id: "$paymentId") { id } }""", user = employee))).endsWith("is not an admin")
        val deleted = graphql("""mutation { deletePayment(id: "$paymentId") { id paymentInformation { id user { id } } } }""", user = admin)
        assertThat(deleted["errors"]).isNull()
        assertThat(deleted["data"]["deletePayment"]["paymentInformation"]["user"]["id"].asString()).isEqualTo(userId.toString())
        assertThat(count("""{paymentInformationId: "$infoId"}""")).isEqualTo(0)
    }

    @Test
    fun `dapr subscriptions are generated for both topics`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val subscriptions = mapper.readTree(body).values().associate { it["topic"].asString() to it["route"].asString() }
        assertThat(subscriptions).isEqualTo(
            mapOf(
                "discount/order/validation-succeeded" to "/subscription/discount/order/validation-succeeded",
                "user/user/created" to "/subscription/user/user/created"
            )
        )
    }
}
