package org.misarch.payment

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.nest.ProcessTerminator
import org.misarch.payment.provider.PaymentProcess
import org.misarch.payment.service.PaymentMethods
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import java.io.File
import java.net.InetSocketAddress
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class RecordingTerminator : ProcessTerminator {
    val errors: MutableList<Throwable> = CopyOnWriteArrayList()
    override fun terminate(error: Throwable) {
        errors += error
    }
}

@TestConfiguration
class PaymentTestConfiguration {
    @Bean
    @Primary
    fun recordingTerminator() = RecordingTerminator()
}

/** The payment provider stub: records requests; `/payment/register` → 201, anything else → 404. */
class FakeSimulation {
    data class Request(val path: String, val headers: Map<String, String>, val body: String)

    val requests: MutableList<Request> = CopyOnWriteArrayList()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                val body = it.requestBody.readAllBytes().toString(Charsets.UTF_8)
                requests += Request(it.requestURI.path, it.requestHeaders.entries.associate { e -> e.key.lowercase() to e.value.first() }, body)
                it.sendResponseHeaders(if (it.requestURI.path == "/payment/register") 201 else 404, -1)
            }
        }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}"
}

/**
 * Behaviour of the payment port against Postgres (Testcontainers), a fake Dapr sidecar and a fake simulation.
 * Expected texts/shapes were recorded from the running original (docs/specs/payment.md §4.4, §6, §11).
 */
@Import(PaymentTestConfiguration::class)
class PaymentIntegrationTest : MisarchIntegrationTest() {

    @Autowired
    lateinit var terminator: RecordingTerminator

    @Autowired
    lateinit var paymentProcess: PaymentProcess


    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")
    private val admin = authorizedUserHeader(UUID.randomUUID(), "admin")

    @BeforeEach
    fun resetSimulation() = simulation.requests.clear()

    private fun post(route: String, body: String): Pair<Int, String> {
        val result = webTestClient.post().uri(route)
            .header("Content-Type", "application/json")
            .bodyValue(body)
            .exchange()
            .returnResult(String::class.java)
        return result.status.value() to result.responseBody.collectList().block()!!.joinToString("")
    }

    private fun event(route: String, data: String) =
        post(route, """{"id":"${UUID.randomUUID()}","topic":"t","specversion":"1.0","data":$data}""")

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in time")
            Thread.sleep(50)
        }
    }

    private fun buyer(id: UUID) = authorizedUserHeader(id, "buyer")

    /** Registers a user (user/user/created) and returns its payment informations by method. */
    private fun newUser(): Pair<UUID, Map<String, String>> {
        val id = UUID.randomUUID()
        val (status, _) = event("/user-created", """{"id":"$id","username":"u","firstName":"f","lastName":"l","dateJoined":"2026-01-01T00:00:00Z"}""")
        assertThat(status).isEqualTo(201)
        var infos: Map<String, String> = emptyMap()
        await {
            infos = infosOf(id)
            infos.size == 2
        }
        return id to infos
    }

    private fun infosOf(userId: UUID): Map<String, String> {
        val r = graphql(
            """{ _entities(representations: [{__typename: "User", id: "$userId"}]) { ... on User { paymentInformations { nodes { id paymentMethod } } } } }""",
            user = buyer(userId)
        )
        return r["data"]["_entities"][0]["paymentInformations"]["nodes"].values().associate { it["paymentMethod"].asString() to it["id"].asString() }
    }

    private fun order(paymentInformationId: String, id: String = UUID.randomUUID().toString(), amount: String = "1999") = """
        {"id":"$id","userId":"${UUID.randomUUID()}","createdAt":"2026-10-13T12:00:00.123Z","orderStatus":"PLACED",
         "placedAt":"2026-10-13T12:00:01.000Z","rejectionReason":null,
         "orderItems":[{"id":"${UUID.randomUUID()}","createdAt":"2026-10-13T12:00:00.123Z","productVariantVersionId":"${UUID.randomUUID()}","productVariantId":"${UUID.randomUUID()}","taxRateVersionId":"${UUID.randomUUID()}","shoppingCartItemId":"${UUID.randomUUID()}","count":1,"compensatableAmount":1999,"shipmentMethodId":"${UUID.randomUUID()}","discountIds":[]}],
         "shipmentAddressId":"${UUID.randomUUID()}","invoiceAddressId":"${UUID.randomUUID()}","compensatableOrderAmount":$amount,
         "paymentInformationId":"$paymentInformationId","paymentAuthorization":null,"vatNumber":null}
    """.trimIndent()

    private fun paymentsOf(infoId: String): List<JsonNode> = graphql(
        """{ payments(filter: {paymentInformationId: "$infoId"}) { nodes { id totalAmount status payedAt numberOfRetries paymentInformation { id paymentMethod } } } }""",
        user = employee
    )["data"]["payments"]["nodes"].values().toList()

    private var lastOrderJson = ""

    private fun startPayment(infoId: String): Pair<String, String> {
        val orderId = UUID.randomUUID().toString()
        lastOrderJson = order(infoId, orderId)
        assertThat(event("/order-validation-succeeded", """{"order":$lastOrderJson}""").first).isEqualTo(201)
        var paymentId = ""
        await {
            val p = paymentsOf(infoId)
            if (p.size == 1 && p[0]["status"].asString() == "PENDING") paymentId = p[0]["id"].asString()
            paymentId.isNotEmpty()
        }
        return orderId to paymentId
    }

    // ---------------------------------------------------------------- runtime contract

    @Test
    fun `dapr subscribe and health equal the original`() {
        val subscribe = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertThat(subscribe).isEqualTo(File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/payment.json").readText().trim())
        val health = webTestClient.get().uri("/health").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertThat(health).isEqualTo("""{"status":"OK"}""")
    }

    // ---------------------------------------------------------------- payment informations

    @Test
    fun `user created adds prepayment and invoice, without idempotency`() {
        val (id, infos) = newUser()
        assertThat(infos.keys).containsExactlyInAnyOrder("PREPAYMENT", "INVOICE")
        event("/user-created", """{"id":"$id","username":"u","firstName":"f","lastName":"l","dateJoined":"x"}""")
        await { graphql("""{ _entities(representations: [{__typename: "User", id: "$id"}]) { ... on User { paymentInformations { totalCount } } } }""", user = buyer(id))["data"]["_entities"][0]["paymentInformations"]["totalCount"].asInt() == 4 }
        val (status, body) = event("/user-created", """{"id":"nope","username":1}""")
        assertThat(status).isEqualTo(400)
        assertThat(body).isEqualTo("""{"message":["id must be a UUID","username must be a string","firstName must be a string","lastName must be a string","dateJoined must be a string"],"error":"Bad Request","statusCode":400}""")
    }

    @Test
    fun `credit card information is masked, validated and only for buyers`() {
        val (userId, _) = newUser()
        val created = graphql(
            """mutation { createCreditCardPaymentInformation(input: {cardHolder: "A B", cardNumber: "4111111111111111", expirationDate: "12/30"}) { id paymentMethod publicMethodDetails user { id } } }""",
            user = buyer(userId)
        )["data"]["createCreditCardPaymentInformation"]
        assertThat(created["paymentMethod"].asString()).isEqualTo("CREDIT_CARD")
        assertThat(created["publicMethodDetails"].toString()).isEqualTo("""{"cardHolder":"A B","cardNumber":"************1111","expirationDate":"12/30"}""")
        assertThat(created["user"]["id"].asString()).isEqualTo(userId.toString())

        val spaced = graphql(
            """mutation { createCreditCardPaymentInformation(input: {cardHolder: "A B", cardNumber: "4111 1111 1111 1111", expirationDate: "abc"}) { publicMethodDetails } }""",
            user = buyer(userId)
        )["data"]["createCreditCardPaymentInformation"]
        assertThat(spaced["publicMethodDetails"]["cardNumber"].asString()).isEqualTo("4111 1111 1111 1111")

        val invalid = graphql(
            """mutation { createCreditCardPaymentInformation(input: {cardHolder: "A", cardNumber: "1234", expirationDate: "01/20"}) { id } }""",
            user = buyer(userId)
        )
        assertThat(invalid["errors"][0]["message"].asString()).isEqualTo("Bad Request Exception")
        assertThat(invalid["errors"][0]["extensions"].toString()).isEqualTo(
            """{"code":"BAD_REQUEST","originalError":{"message":["The credit card number is invalid","The expiration date is invalid or expired"],"error":"Bad Request","statusCode":400}}"""
        )
        assertThat(invalid["data"].isNull).isTrue()
        val asEmployee = graphql(
            """mutation { createCreditCardPaymentInformation(input: {cardHolder: "A", cardNumber: "4111111111111111", expirationDate: "12/30"}) { id } }""",
            user = employee
        )
        assertThat(asEmployee["errors"][0]["extensions"]["code"].asString()).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `payment information access and the quirky filters`() {
        val (userId, infos) = newUser()
        val foreign = graphql(
            """{ _entities(representations: [{__typename: "User", id: "$userId"}]) { ... on User { paymentInformations { totalCount } } } }""",
            user = buyer(UUID.randomUUID())
        )
        assertThat(foreign["data"]["_entities"][0]["paymentInformations"].isNull).isTrue()
        assertThat(foreign["errors"][0]["message"].asString()).isEqualTo("User not authorized to access foreign Payment Informations}")
        assertThat(foreign["errors"][0]["extensions"]["code"].asString()).isEqualTo("UNAUTHENTICATED")
        assertThat(foreign["errors"][0]["path"].values().map { it.asString() }).containsExactly("_entities", "0", "paymentInformations")

        val filtered = graphql(
            """{ _entities(representations: [{__typename: "User", id: "$userId"}]) { ... on User { paymentInformations(filter: {paymentMethod: INVOICE}) { nodes { id } totalCount } } } }""",
            user = employee
        )["data"]["_entities"][0]["paymentInformations"]
        assertThat(filtered["nodes"].values().map { it["id"].asString() }).containsExactly(infos["INVOICE"])

        val byUser = graphql("""{ paymentInformations(filter: {user: "$userId"}) { nodes { id } totalCount } }""", user = employee)
        assertThat(byUser["data"]["paymentInformations"]["nodes"].size()).isEqualTo(0)
        assertThat(byUser["data"]["paymentInformations"]["totalCount"].asInt()).isEqualTo(0)

        val noDirection = graphql("""{ paymentInformations(orderBy: {field: ID}) { nodes { id } } }""", user = employee)
        assertThat(noDirection["errors"][0]["message"].asString()).isEqualTo("Invalid sort value: { _id: undefined }")
        assertThat(noDirection["data"].isNull).isTrue()
        val empty = graphql("""{ paymentInformations(orderBy: {}) { nodes { id } } }""", user = employee)
        assertThat(empty["errors"][0]["message"].asString()).isEqualTo("Invalid sort value: { undefined: undefined }")
        val inline = graphql("""{ paymentInformations { ... on PaymentInformationConnection { totalCount } } }""", user = employee)
        assertThat(inline["errors"][0]["message"].asString()).isEqualTo("Cannot read properties of undefined (reading 'value')")
        val spread = graphql("""query { paymentInformations { ...F } } fragment F on PaymentInformationConnection { totalCount }""", user = employee)
        assertThat(spread["errors"][0]["message"].asString()).isEqualTo("Cannot return null for non-nullable field PaymentInformationConnection.totalCount.")

        val ids = graphql("""{ paymentInformations(orderBy: {field: ID, direction: DESC}) { nodes { id } totalCount hasNextPage } }""", user = employee)["data"]["paymentInformations"]
        val list = ids["nodes"].values().map { it["id"].asString() }
        assertThat(list).isEqualTo(list.sortedDescending())
        assertThat(ids["totalCount"].asInt()).isEqualTo(list.size)
    }

    @Test
    fun `buyers cannot delete their own payment information, employees can`() {
        val (userId, infos) = newUser()
        val own = graphql("""mutation { deletePaymentInformation(id: "${infos["INVOICE"]}") { id } }""", user = buyer(userId))
        assertThat(own["errors"][0]["message"].asString()).isEqualTo("User not authorized to delete Payment Information with id \"${infos["INVOICE"]}\"")
        assertThat(own["errors"][0]["extensions"]["originalError"]["statusCode"].asInt()).isEqualTo(401)
        val missing = UUID.randomUUID()
        val notFound = graphql("""mutation { deletePaymentInformation(id: "$missing") { id } }""", user = employee)["errors"][0]
        assertThat(notFound["message"].asString()).isEqualTo("Payment Information with id \"$missing\" not found")
        assertThat(notFound["extensions"].toString()).isEqualTo(
            """{"code":"INTERNAL_SERVER_ERROR","status":404,"originalError":{"message":"Payment Information with id \"$missing\" not found","error":"Not Found","statusCode":404}}"""
        )
        val deleted = graphql("""mutation { deletePaymentInformation(id: "${infos["INVOICE"]}") { id paymentMethod } }""", user = employee)
        assertThat(deleted["data"]["deletePaymentInformation"]["paymentMethod"].asString()).isEqualTo("INVOICE")
    }

    // ---------------------------------------------------------------- payment process

    @Test
    fun `invoice payment publishes payment-enabled, registers at the provider and succeeds by callback`() {
        val (_, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val (orderId, paymentId) = startPayment(infoId)
        val enabled = dapr.awaitPublished("payment/payment/payment-enabled").filter { it.json["order"]["id"].asString() == orderId }
        assertThat(enabled).hasSize(1)
        assertThat(enabled[0].json["order"]).isEqualTo(mapper.readTree(lastOrderJson))
        assertThat(enabled[0].body).contains("\"rejectionReason\":null").contains("\"paymentAuthorization\":null")
        await { simulation.requests.any { it.body.contains(paymentId) } }
        val register = simulation.requests.first { it.body.contains(paymentId) }
        assertThat(register.path).isEqualTo("/payment/register")
        assertThat(register.body).isEqualTo("""{"paymentId":"$paymentId","amount":1999,"paymentType":"invoice"}""")
        assertThat(register.headers["content-type"]).startsWith("application/json")

        val payment = paymentsOf(infoId)[0]
        assertThat(payment.toString()).contains("\"totalAmount\":1999,").contains("\"numberOfRetries\":0,")
        assertThat(payment["payedAt"].isNull).isTrue()

        val (status, body) = post("/payment/update-payment-status", """{"paymentId":"$paymentId","status":"SUCCEEDED"}""")
        assertThat(status).isEqualTo(201)
        val json = mapper.readTree(body)
        assertThat(json.propertyNames().toList()).containsExactly("_id", "totalAmount", "status", "paymentInformation", "createdAt", "updatedAt", "payedAt", "id")
        assertThat(json["status"].asString()).isEqualTo("SUCCEEDED")
        assertThat(json["paymentInformation"].propertyNames().toList()).containsExactly("_id", "paymentMethod", "user", "id")
        assertThat(json["payedAt"].asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
        assertThat(paymentsOf(infoId)[0]["payedAt"].asString()).isEqualTo(json["payedAt"].asString())
    }

    @Test
    fun `failed credit card payments retry at the wrong provider path and stay pending`() {
        val (userId, _) = newUser()
        val cardId = graphql(
            """mutation { createCreditCardPaymentInformation(input: {cardHolder: "A", cardNumber: "4111111111111111", expirationDate: "12/30"}) { id } }""",
            user = buyer(userId)
        )["data"]["createCreditCardPaymentInformation"]["id"].asString()
        val (orderId, paymentId) = startPayment(cardId)
        assertThat(dapr.awaitPublished("payment/payment/payment-enabled").filter { it.json["order"]["id"].asString() == orderId }).hasSize(1)
        val (status, body) = post("/payment/update-payment-status", """{"paymentId":"$paymentId","status":"FAILED"}""")
        assertThat(status).isEqualTo(201)
        assertThat(body).isEmpty()
        val retry = simulation.requests.first { it.path == "/register" }
        assertThat(retry.body).isEqualTo("""{"paymentId":"$paymentId","type":"credit-card"}""")
        assertThat(paymentsOf(cardId)[0]["status"].asString()).isEqualTo("PENDING")
        assertThat(dapr.published("payment/payment/payment-failed").filter { it.json["order"]["id"].asString() == orderId }).isEmpty()
    }

    @Test
    fun `prepayment is enabled by the success callback but keeps its status, the cron later fails it`() {
        val (_, infos) = newUser()
        val infoId = infos.getValue("PREPAYMENT")
        val (orderId, paymentId) = startPayment(infoId)
        Thread.sleep(300)
        assertThat(dapr.published("payment/payment/payment-enabled").filter { it.json["order"]["id"].asString() == orderId }).isEmpty()
        val (status, body) = post("/payment/update-payment-status", """{"paymentId":"$paymentId","status":"SUCCEEDED"}""")
        assertThat(status to body).isEqualTo(201 to "")
        assertThat(dapr.awaitPublished("payment/payment/payment-enabled").filter { it.json["order"]["id"].asString() == orderId }).hasSize(1)
        assertThat(paymentsOf(infoId)[0]["status"].asString()).isEqualTo("PENDING")

        // 8 days later the prepayment cron fails the (successful) payment
        runBlocking { paymentProcess.checkOpenPayments(PaymentMethods.PREPAYMENT, 7, ZonedDateTime.now().plusDays(8)) }
        assertThat(dapr.awaitPublished("payment/payment/payment-failed").filter { it.json["order"]["id"].asString() == orderId }).hasSize(1)
        await { paymentsOf(infoId)[0]["status"].asString() == "FAILED" }
    }

    @Test
    fun `invoice cron fails only payments older than 30 days`() {
        val (_, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val (orderId, _) = startPayment(infoId)
        runBlocking { paymentProcess.checkOpenPayments(PaymentMethods.INVOICE, 30, ZonedDateTime.now().plusDays(29)) }
        Thread.sleep(300)
        assertThat(paymentsOf(infoId)[0]["status"].asString()).isEqualTo("PENDING")
        runBlocking { paymentProcess.checkOpenPayments(PaymentMethods.INVOICE, 30, ZonedDateTime.now().plusDays(31)) }
        assertThat(dapr.awaitPublished("payment/payment/payment-failed").filter { it.json["order"]["id"].asString() == orderId }).hasSize(1)
        await { paymentsOf(infoId)[0]["status"].asString() == "FAILED" }
    }

    @Test
    fun `unknown payment information publishes payment-failed and terminates like the original`() {
        val before = terminator.errors.size
        val orderId = UUID.randomUUID().toString()
        assertThat(event("/order-validation-succeeded", """{"order":${order(UUID.randomUUID().toString(), orderId)}}""").first).isEqualTo(201)
        val failed = dapr.awaitPublished("payment/payment/payment-failed").filter { it.json["order"]["id"].asString() == orderId }
        assertThat(failed).hasSize(1)
        await { terminator.errors.size > before }
        assertThat(terminator.errors.last().message).isEqualTo("Open order for payment: $orderId not found")
        assertThat(event("/order-validation-succeeded", "{}").second)
            .isEqualTo("""{"message":["order must be an object"],"error":"Bad Request","statusCode":400}""")
    }

    @Test
    fun `provider callback errors answer like Nest`() {
        val missing = UUID.randomUUID()
        assertThat(post("/payment/update-payment-status", """{"paymentId":"$missing","status":"SUCCEEDED"}"""))
            .isEqualTo(404 to """{"message":"Payment with id \"$missing\" not found","error":"Not Found","statusCode":404}""")
        assertThat(post("/payment/update-payment-status", """{"paymentId":5,"status":"DONE"}"""))
            .isEqualTo(400 to """{"message":["paymentId must be a string","status must be one of the following values: OPEN, PENDING, SUCCEEDED, FAILED, INKASSO"],"error":"Bad Request","statusCode":400}""")

        val (_, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val (_, paymentId) = startPayment(infoId)
        graphql("""mutation { deletePaymentInformation(id: "$infoId") { id } }""", user = employee)
        assertThat(post("/payment/update-payment-status", """{"paymentId":"$paymentId","status":"SUCCEEDED"}"""))
            .isEqualTo(500 to """{"statusCode":500,"message":"Internal server error"}""")
        val listed = graphql("""{ payments(filter: {paymentInformationId: "$infoId"}) { nodes { id paymentInformation { id } } } }""", user = employee)
        assertThat(listed["errors"][0]["message"].asString()).isEqualTo("Cannot return null for non-nullable field Payment.paymentInformation.")
    }

    @Test
    fun `payments filters, entities and deletePayment`() {
        val (userId, infos) = newUser()
        val infoId = infos.getValue("INVOICE")
        val (_, paymentId) = startPayment(infoId)
        fun count(filter: String) = graphql("""{ payments(filter: $filter) { totalCount } }""", user = employee)["data"]["payments"]["totalCount"].asInt()
        assertThat(count("""{paymentInformationId: "$infoId"}""")).isEqualTo(1)
        assertThat(count("""{paymentInformationId: "$infoId", paymentMethod: PREPAYMENT}""")).isEqualTo(0)
        assertThat(count("""{paymentInformationId: "$infoId", paymentMethod: INVOICE, status: PENDING}""")).isEqualTo(1)
        assertThat(count("""{paymentInformationId: "$infoId", from: "2020-01-01", to: "2099-01-01T00:00:00+02:00"}""")).isEqualTo(1)
        assertThat(count("""{paymentInformationId: "$infoId", from: "2099-01-01"}""")).isEqualTo(0)
        val bad = graphql("""{ payments(filter: {from: "garbage"}) { totalCount } }""", user = employee)
        assertThat(bad["errors"][0]["message"].asString())
            .isEqualTo("Cast to date failed for value \"Invalid Date\" (type Date) at path \"createdAt\" for model \"Payment\"")

        // owner sees the payments of the information, others do not
        val ownPayments = graphql(
            """{ _entities(representations: [{__typename: "PaymentInformation", id: "$infoId"}]) { ... on PaymentInformation { payments { nodes { id } totalCount } } } }""",
            user = buyer(userId)
        )["data"]["_entities"][0]["payments"]
        assertThat(ownPayments["totalCount"].asInt()).isEqualTo(1)
        val foreign = graphql(
            """{ _entities(representations: [{__typename: "PaymentInformation", id: "$infoId"}]) { ... on PaymentInformation { payments { totalCount } } } }""",
            user = buyer(UUID.randomUUID())
        )
        assertThat(foreign["errors"][0]["message"].asString()).isEqualTo("User not authorized to access foreign Payments}")

        // entities are public; a missing one is null with the Nest error at its index
        val missing = UUID.randomUUID()
        val entities = graphql(
            """{ _entities(representations: [{__typename: "Payment", id: "$paymentId"}, {__typename: "Payment", id: "$missing"}]) { ... on Payment { id status } } }"""
        )
        assertThat(entities["data"]["_entities"][0]["id"].asString()).isEqualTo(paymentId)
        assertThat(entities["data"]["_entities"][1].isNull).isTrue()
        assertThat(entities["errors"][0]["message"].asString()).isEqualTo("Payment with id \"$missing\" not found")
        assertThat(entities["errors"][0]["path"].values().map { it.asString() }).containsExactly("_entities", "1")

        assertThat(graphql("""mutation { deletePayment(id: "$paymentId") { id } }""", user = employee)["errors"][0]["message"].asString())
            .isEqualTo("Forbidden resource")
        val withInfo = graphql("""mutation { deletePayment(id: "$paymentId") { id status paymentInformation { id } } }""", user = admin)
        assertThat(withInfo["errors"][0]["message"].asString()).isEqualTo("Cannot return null for non-nullable field PaymentInformation.id.")
        assertThat(withInfo["data"].isNull).isTrue()
        assertThat(count("""{paymentInformationId: "$infoId"}""")).isEqualTo(0)
    }

    companion object {
        val simulation = FakeSimulation()

        @JvmStatic
        @DynamicPropertySource
        fun simulationProperties(registry: DynamicPropertyRegistry) {
            registry.add("misarch.payment.simulation-url") { simulation.url }
        }
    }
}
