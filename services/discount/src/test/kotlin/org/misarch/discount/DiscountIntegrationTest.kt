package org.misarch.discount

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.json.JsonCompareMode
import java.io.File
import java.util.UUID

/**
 * Behaviour of the discount service against a real Postgres (Testcontainers) and a fake Dapr sidecar:
 * schema/migration, replication events, mutations + published events, queries, connections, federation,
 * authorization. The saga step (`reservation-succeeded`) is covered by [DiscountValidationIntegrationTest].
 */
class DiscountIntegrationTest : DiscountTestSupport() {

    // ------------------------------------------------------------------------------------------------ runtime contract

    @Test
    fun `dapr subscribe equals the original's subscription list`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/discount.json").readText()
        fun normalize(json: String) = mapper.readTree(json).toList()
            .map { "${it["pubsubName"].asString()} ${it["topic"].asString()} ${it["route"].asString()} ${it["metadata"]}" }
            .toSortedSet()
        assertThat(normalize(body)).isEqualTo(normalize(reference))
        assertThat(normalize(body)).hasSize(5)
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", JsonCompareMode.STRICT)
    }

    @Test
    fun `the original migration is applied unchanged with its checksum, triggers and functions`() {
        val history = sql("SELECT version, checksum, success FROM flyway_schema_history WHERE version = '1.1'").single()
        assertThat(history["checksum"]).isEqualTo(247510061)
        assertThat(history["success"]).isEqualTo(true)
        val triggers = sql(
            "SELECT trigger_name, event_object_table, event_manipulation FROM information_schema.triggers " +
                "WHERE trigger_schema = 'public'"
        ).map { "${it["trigger_name"]} ${it["event_object_table"]} ${it["event_manipulation"]}" }
        assertThat(triggers).contains(
            "before_insert_update_coupon_usages couponredemptionentity INSERT",
            "check_discount_usages_trigger discountusageentity INSERT",
            "check_discount_usages_trigger discountusageentity UPDATE",
        )
        val columns = sql(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'discountusageentity' " +
                "AND column_name IN ('discountid', 'userid', 'usages', 'id')"
        )
        assertThat(columns).hasSize(4)
    }

    // ----------------------------------------------------------------------------------------------- replication events

    @Test
    fun `replication events create replica rows and duplicates are acknowledged without change`() {
        val userId = registerUser()
        val categoryIds = listOf(registerCategory(), registerCategory())
        val productId = registerProduct(categoryIds)
        val variantId = registerVariant(productId)
        assertThat(sql("SELECT id FROM UserEntity WHERE id = '$userId'")).hasSize(1)
        assertThat(sql("SELECT categoryId FROM ProductToCategoryEntity WHERE productId = '$productId'").map { it["categoryid"] })
            .containsExactlyInAnyOrderElementsOf(categoryIds)
        assertThat(sql("SELECT productId FROM ProductVariantEntity WHERE id = '$variantId'").single()["productid"])
            .isEqualTo(productId)

        // FIXED (Q6): idempotent handlers - a redelivered event is acknowledged and changes nothing (was 500 forever)
        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$userId"}""")).isEqualTo(200)
        assertThat(deliverEvent("/subscription/catalog/category/created", "catalog/category/created", """{"id":"${categoryIds[0]}"}"""))
            .isEqualTo(200)
        assertThat(deliverEvent("/subscription/catalog/product-variant/created", "catalog/product-variant/created",
            """{"id":"$variantId","productId":"$productId"}""")).isEqualTo(200)
        assertThat(deliverEvent("/subscription/catalog/product/created", "catalog/product/created",
            """{"id":"$productId","categoryIds":["${categoryIds[0]}","${categoryIds[1]}"]}""")).isEqualTo(200)
        assertThat(sql("SELECT id FROM UserEntity WHERE id = '$userId'")).hasSize(1)
        assertThat(sql("SELECT id FROM ProductToCategoryEntity WHERE productId = '$productId'")).hasSize(2)

        // duplicate category ids in one event: one link, no failure
        val category = registerCategory()
        val product = UUID.randomUUID()
        val status = deliverEvent("/subscription/catalog/product/created", "catalog/product/created",
            """{"id":"$product","categoryIds":["$category","$category"]}""")
        assertThat(status).isEqualTo(200)
        assertThat(sql("SELECT id FROM ProductEntity WHERE id = '$product'")).hasSize(1)
        assertThat(sql("SELECT id FROM ProductToCategoryEntity WHERE productId = '$product'")).hasSize(1)

        // variants have no foreign key to products; a missing required field is a client error
        val orphan = registerVariant(UUID.randomUUID())
        assertThat(sql("SELECT id FROM ProductVariantEntity WHERE id = '$orphan'")).hasSize(1)
        assertThat(deliverEvent("/subscription/catalog/product-variant/created", "catalog/product-variant/created",
            """{"id":"${UUID.randomUUID()}"}""")).isEqualTo(400)
    }

    // ------------------------------------------------------------------------------------------------------- discounts

    @Test
    fun `createDiscount stores the discount and its targets and publishes discount created`() {
        val categoryId = registerCategory()
        val productId = registerProduct(listOf(categoryId))
        val variantId = registerVariant(productId)
        val response = createDiscountResponse(
            discount = 0.25, maxUsagesPerUser = 3, validFrom = "2020-01-01T02:00:00+02:00", minOrderAmount = null,
            categoryIds = listOf(categoryId, categoryId), productIds = listOf(productId), variantIds = listOf(variantId)
        )
        assertThat(response["errors"]).isNull()
        val discount = response["data"]["createDiscount"]
        val discountId = discount["id"].asString()
        assertThat(discount["discount"].asDouble()).isEqualTo(0.25)
        assertThat(discount["maxUsagesPerUser"].asInt()).isEqualTo(3)
        assertThat(discount["minOrderAmount"].isNull).isTrue()
        // returned from memory (input offset kept), like the original
        assertThat(discount["validFrom"].asString()).isEqualTo("2020-01-01T02:00:00.000+02:00")

        val events = dapr.awaitPublished("discount/discount/created")
        assertThat(events).hasSize(1)
        assertThat(events[0].pubsubName).isEqualTo("pubsub")
        assertThat(events[0].json.toString()).isEqualTo(
            """{"id":"$discountId","discount":0.25,"maxUsagesPerUser":3,"validUntil":"2099-01-01T00:00:00Z",""" +
                """"validFrom":"2020-01-01T02:00:00+02:00","discountAppliesToCategoryIds":["$categoryId"],""" +
                """"discountAppliesToProductIds":["$productId"],"discountAppliesToProductVariantIds":["$variantId"]}"""
        )
        assertThat(sql("SELECT categoryId FROM DiscountToCategoryEntity WHERE discountId = '$discountId'")).hasSize(1)

        val byId = graphql(
            """{ discount(id: "$discountId") { id validFrom discountAppliesToCategories { totalCount nodes { id } }
               discountAppliesToProducts { totalCount nodes { id } } discountAppliesToProductVariants { totalCount nodes { id } } } }""",
            user = employee
        )
        val loaded = byId["data"]["discount"]
        assertThat(loaded["validFrom"].asString()).isEqualTo("2020-01-01T00:00:00.000Z")
        assertThat(loaded["discountAppliesToCategories"]["nodes"][0]["id"].asString()).isEqualTo(categoryId.toString())
        assertThat(loaded["discountAppliesToProducts"]["nodes"][0]["id"].asString()).isEqualTo(productId.toString())
        assertThat(loaded["discountAppliesToProductVariants"]["nodes"][0]["id"].asString()).isEqualTo(variantId.toString())
    }

    @Test
    fun `createDiscount validates input and authorization with the original messages`() {
        val categoryId = registerCategory()
        assertThat(errorMessage(createDiscountResponse(categoryIds = listOf(categoryId), user = null)))
            .isEqualTo("Exception while fetching data (/createDiscount) : Unauthorized access")
        val buyerId = UUID.randomUUID()
        assertThat(errorMessage(createDiscountResponse(categoryIds = listOf(categoryId), user = authorizedUserHeader(buyerId, "buyer"))))
            .isEqualTo("Exception while fetching data (/createDiscount) : Unauthorized access: $buyerId is not an employee or admin")
        assertThat(errorMessage(createDiscountResponse()))
            .isEqualTo("Exception while fetching data (/createDiscount) : At least one category, product or product variant must be specified")
        val unknown = UUID.randomUUID()
        assertThat(errorMessage(createDiscountResponse(categoryIds = listOf(unknown))))
            .isEqualTo("Exception while fetching data (/createDiscount) : Categories with ids [$unknown] do not exist")
        assertThat(errorMessage(createDiscountResponse(productIds = listOf(unknown))))
            .endsWith("Products with ids [$unknown] do not exist")
        assertThat(errorMessage(createDiscountResponse(variantIds = listOf(unknown))))
            .endsWith("Product variants with ids [$unknown] do not exist")
        assertThat(errorMessage(createDiscountResponse(discount = 1.5, categoryIds = listOf(categoryId))))
            .endsWith("A discount cannot be larger than 1")
        assertThat(errorMessage(createDiscountResponse(discount = -0.1, categoryIds = listOf(categoryId))))
            .endsWith("A discount cannot increase the price and thus must be >= 0")
        // admin is an employee too
        val admin = authorizedUserHeader(UUID.randomUUID(), "admin")
        assertThat(createDiscountResponse(categoryIds = listOf(categoryId), user = admin)["errors"]).isNull()
        assertThat(dapr.published("discount/discount/created")).hasSize(1)
    }

    @Test
    fun `updateDiscount changes fields and targets and publishes the targets read from the database`() {
        val categoryA = registerCategory()
        val categoryB = registerCategory()
        val productId = registerProduct()
        val discountId = createDiscount(maxUsagesPerUser = 4, minOrderAmount = 100, categoryIds = listOf(categoryA))
        dapr.reset()

        val response = graphql(
            """
            mutation { updateDiscount(input: {id: "$discountId", maxUsagesPerUser: null, validUntil: "2098-06-01T12:30:00Z",
              addedDiscountAppliesToCategoryIds: ["$categoryB"], removedDiscountAppliesToCategoryIds: ["$categoryA"],
              addedDiscountAppliesToProductIds: ["$productId"]}) { id maxUsagesPerUser minOrderAmount validUntil } }
            """.trimIndent(),
            user = employee
        )
        assertThat(response["errors"]).isNull()
        val updated = response["data"]["updateDiscount"]
        assertThat(updated["maxUsagesPerUser"].isNull).isTrue()
        assertThat(updated["minOrderAmount"].asInt()).isEqualTo(100) // omitted -> unchanged
        assertThat(updated["validUntil"].asString()).isEqualTo("2098-06-01T12:30:00.000Z")

        val event = dapr.awaitPublished("discount/discount/updated").single().json
        assertThat(event.has("maxUsagesPerUser")).isFalse() // null -> omitted (Dapr SDK serializer)
        assertThat(event["minOrderAmount"].asInt()).isEqualTo(100)
        assertThat(event["validUntil"].asString()).isEqualTo("2098-06-01T12:30:00Z")
        assertThat(event["discountAppliesToCategoryIds"].toList().map { it.asString() }).containsExactly(categoryB.toString())
        assertThat(event["discountAppliesToProductIds"].toList().map { it.asString() }).containsExactly(productId.toString())
        assertThat(event["discountAppliesToProductVariantIds"].size()).isEqualTo(0)
        val row = sql("SELECT maxUsagesPerUser, minOrderAmount FROM DiscountEntity WHERE id = '$discountId'").single()
        assertThat(row["maxusagesperuser"]).isNull()
        assertThat(row["minorderamount"]).isEqualTo(100)

        // explicit null clears minOrderAmount
        graphql("""mutation { updateDiscount(input: {id: "$discountId", minOrderAmount: null}) { id } }""", user = employee)
        assertThat(sql("SELECT minOrderAmount FROM DiscountEntity WHERE id = '$discountId'").single()["minorderamount"]).isNull()

        val unknownId = UUID.randomUUID()
        assertThat(errorMessage(graphql("""mutation { updateDiscount(input: {id: "$unknownId"}) { id } }""", user = employee)))
            .isEqualTo("Exception while fetching data (/updateDiscount) : null")
        val unknownCategory = UUID.randomUUID()
        assertThat(errorMessage(graphql(
            """mutation { updateDiscount(input: {id: "$discountId", addedDiscountAppliesToCategoryIds: ["$unknownCategory"]}) { id } }""",
            user = employee
        ))).endsWith("Categories with ids [$unknownCategory] do not exist")
        assertThat(errorMessage(graphql(
            """mutation { updateDiscount(input: {id: "$discountId"}) { id } }""", user = authorizedUserHeader(UUID.randomUUID(), "buyer")
        ))).contains("is not an employee or admin")
    }

    @Test
    fun `failing publish fails the mutation and rolls back`() {
        val categoryId = registerCategory()
        dapr.publishStatus = 500
        val response = createDiscountResponse(categoryIds = listOf(categoryId))
        assertThat(response["errors"]).isNotNull()
        dapr.publishStatus = 204
        assertThat(sql("SELECT id FROM DiscountToCategoryEntity WHERE categoryId = '$categoryId'")).isEmpty()
    }

    // --------------------------------------------------------------------------------------------------------- coupons

    @Test
    fun `createCoupon and updateCoupon publish events, validate and authorize`() {
        val discountId = createDiscount(categoryIds = listOf(registerCategory()))
        val code = "SUMMER-" + UUID.randomUUID()
        val response = createCouponResponse(discountId, code = code, maxUsages = 2)
        assertThat(response["errors"]).isNull()
        val coupon = response["data"]["createCoupon"]
        val couponId = coupon["id"].asString()
        assertThat(coupon["usages"].asInt()).isEqualTo(0)
        assertThat(coupon["maxUsages"].asInt()).isEqualTo(2)
        assertThat(coupon["discount"]["id"].asString()).isEqualTo(discountId.toString())
        assertThat(dapr.awaitPublished("discount/coupon/created").single().json.toString()).isEqualTo(
            """{"id":"$couponId","maxUsages":2,"validUntil":"2099-01-01T00:00:00Z","validFrom":"2020-01-01T00:00:00Z",""" +
                """"code":"$code","discountId":"$discountId"}"""
        )

        assertThat(errorMessage(createCouponResponse(discountId, code = code)))
            .isEqualTo("Exception while fetching data (/createCoupon) : Coupon with code $code already exists")
        val unknown = UUID.randomUUID()
        assertThat(errorMessage(createCouponResponse(unknown)))
            .isEqualTo("Exception while fetching data (/createCoupon) : Discount with id $unknown does not exist")
        assertThat(errorMessage(createCouponResponse(discountId, user = authorizedUserHeader(UUID.randomUUID(), "buyer"))))
            .contains("is not an employee or admin")

        // update: null clears maxUsages (omitted in the event), omitted fields stay
        val newCode = "WINTER-" + UUID.randomUUID()
        val updated = graphql(
            """mutation { updateCoupon(input: {id: "$couponId", code: "$newCode", maxUsages: null}) { id code maxUsages validFrom } }""",
            user = employee
        )
        assertThat(updated["errors"]).isNull()
        assertThat(updated["data"]["updateCoupon"]["code"].asString()).isEqualTo(newCode)
        assertThat(updated["data"]["updateCoupon"]["maxUsages"].isNull).isTrue()
        val event = dapr.awaitPublished("discount/coupon/updated").single().json
        assertThat(event.has("maxUsages")).isFalse()
        assertThat(event["code"].asString()).isEqualTo(newCode)
        assertThat(event["validFrom"].asString()).isEqualTo("2020-01-01T00:00:00Z")
        val keep = graphql("""mutation { updateCoupon(input: {id: "$couponId", validUntil: "2097-01-01T00:00:00Z"}) { code maxUsages validUntil } }""", user = employee)
        assertThat(keep["data"]["updateCoupon"]["code"].asString()).isEqualTo(newCode)
        assertThat(keep["data"]["updateCoupon"]["maxUsages"].isNull).isTrue()
        assertThat(errorMessage(graphql("""mutation { updateCoupon(input: {id: "${UUID.randomUUID()}"}) { id } }""", user = employee)))
            .isEqualTo("Exception while fetching data (/updateCoupon) : null")
    }

    @Test
    fun `registerCoupon increments usages through the database trigger and enforces limits`() {
        val discountId = createDiscount(categoryIds = listOf(registerCategory()))
        val couponId = createCoupon(discountId, maxUsages = 2)
        val code = couponCode(couponId)
        val userA = registerUser()
        val userB = registerUser()
        val userC = registerUser()

        // usages requires employee: buyers get an error on that field (the redemption itself is done)
        val first = registerCoupon(code, userA, selection = "id usages")
        assertThat(errorMessage(first)).isEqualTo(
            "Exception while fetching data (/registerCoupon/usages) : Unauthorized access: $userA is not an employee or admin"
        )
        assertThat(sql("SELECT usages FROM CouponEntity WHERE id = '$couponId'").single()["usages"]).isEqualTo(1)
        val byEmployee = registerCoupon(code, userB, employee, selection = "id usages")
        assertThat(byEmployee["data"]["registerCoupon"]["usages"].asInt()).isEqualTo(2)
        assertThat(errorMessage(registerCoupon(code, userC))).contains("Coupon has been used too often")
        assertThat(errorMessage(registerCoupon(code, userA))).contains("couponredemptionentity")
        assertThat(errorMessage(registerCoupon(code, userA, authorizedUserHeader(userB, "buyer"))))
            .isEqualTo("Exception while fetching data (/registerCoupon) : Unauthorized access: $userB is not an employee or admin")
        assertThat(errorMessage(registerCoupon("UNKNOWN-" + UUID.randomUUID(), userA)))
            .contains("Coupon with code UNKNOWN-").endsWith("does not exist")
        val expired = couponCode(createCoupon(discountId, validUntil = "2021-01-01T00:00:00Z"))
        assertThat(errorMessage(registerCoupon(expired, userA)))
            .isEqualTo("Exception while fetching data (/registerCoupon) : Coupon with code $expired is not valid currently")
        assertThat(sql("SELECT usages FROM CouponEntity WHERE id = '$couponId'").single()["usages"]).isEqualTo(2)
    }

    @Test
    fun `coupon and discount queries require employees and unknown ids fail`() {
        val discountId = createDiscount(categoryIds = listOf(registerCategory()))
        val couponId = createCoupon(discountId)
        val buyer = authorizedUserHeader(UUID.randomUUID(), "buyer")
        assertThat(graphql("""{ coupon(id: "$couponId") { id code usages maxUsages discount { id } } }""", user = employee)["data"]["coupon"]["discount"]["id"].asString())
            .isEqualTo(discountId.toString())
        assertThat(errorMessage(graphql("""{ coupon(id: "$couponId") { id } }""", user = buyer))).contains("is not an employee or admin")
        assertThat(errorMessage(graphql("""{ discount(id: "$discountId") { id } }""")))
            .isEqualTo("Exception while fetching data (/discount) : Unauthorized access")
        val unknown = graphql("""{ discount(id: "${UUID.randomUUID()}") { id } }""", user = employee)
        assertThat(unknown["data"]?.isNull ?: true).isTrue()
        // NoSuchElementException without message, the unknown-id convention of the Kotlin originals (Q4 fixed: no NPE)
        assertThat(errorMessage(unknown)).isEqualTo("Exception while fetching data (/discount) : null")
    }

    // ------------------------------------------------------------------------------------------------- connections

    @Test
    fun `discount connections of categories, products and variants paginate and order`() {
        val categoryId = registerCategory()
        val productId = registerProduct()
        val variantId = registerVariant(productId)
        val discounts = listOf("2021-01-01T00:00:00Z", "2020-06-01T00:00:00Z", "2022-01-01T00:00:00Z").map {
            createDiscount(validFrom = it, categoryIds = listOf(categoryId), productIds = listOf(productId), variantIds = listOf(variantId))
        }
        fun connection(typename: String, id: UUID, args: String) = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on $typename { discounts$args { totalCount hasNextPage nodes { id validFrom } } } } }
            """.trimIndent(),
            mapOf("r" to listOf(mapOf("__typename" to typename, "id" to id.toString()))),
            employee
        )["data"]["_entities"][0]["discounts"]

        val byValidFrom = connection("Category", categoryId, "(orderBy: {field: VALID_FROM, direction: DESC})")
        assertThat(byValidFrom["totalCount"].asInt()).isEqualTo(3)
        assertThat(byValidFrom["hasNextPage"].asBoolean()).isFalse()
        assertThat(byValidFrom["nodes"].toList().map { it["id"].asString() })
            .containsExactly(discounts[2].toString(), discounts[0].toString(), discounts[1].toString())

        val page = connection("Product", productId, "(first: 1, skip: 1)")
        assertThat(page["totalCount"].asInt()).isEqualTo(3)
        assertThat(page["hasNextPage"].asBoolean()).isTrue()
        assertThat(page["nodes"].toList().map { it["id"].asString() }).containsExactly(discounts.map { it.toString() }.sorted()[1]) // Postgres orders uuid bytewise = by hex string (java.util.UUID.compareTo is signed)

        val last = connection("ProductVariant", variantId, "(first: 2, skip: 1, orderBy: {direction: DESC})")
        assertThat(last["hasNextPage"].asBoolean()).isFalse()
        assertThat(last["nodes"].toList().map { it["id"].asString() })
            .containsExactly(discounts.map { it.toString() }.sortedDescending()[1], discounts.map { it.toString() }.sortedDescending()[2])

        val buyer = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on Category { discounts { totalCount } } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "Category", "id" to categoryId.toString()))),
            authorizedUserHeader(UUID.randomUUID(), "buyer")
        )
        assertThat(errorMessage(buyer)).contains("is not an employee or admin")
    }

    @Test
    fun `coupon connections honour the authorized user and the userHasCoupon filter`() {
        val discountId = createDiscount(categoryIds = listOf(registerCategory()))
        val couponA = createCoupon(discountId, validFrom = "2020-02-01T00:00:00Z")
        val couponB = createCoupon(discountId, validFrom = "2020-01-01T00:00:00Z")
        val buyerId = registerUser()
        val otherId = registerUser()
        val buyer = authorizedUserHeader(buyerId, "buyer")
        assertThat(registerCoupon(couponCode(couponA), buyerId, employee)["errors"]).isNull()
        assertThat(registerCoupon(couponCode(couponB), otherId, employee)["errors"]).isNull()

        fun requiresCoupon(args: String, user: String?) = graphql(
            """{ discount(id: "$discountId") { id } }""", user = employee
        ).let {
            graphql(
                """
                query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
                  ... on Discount { discountRequiresCoupon$args { totalCount hasNextPage nodes { id } } } } }
                """.trimIndent(),
                mapOf("r" to listOf(mapOf("__typename" to "Discount", "id" to discountId.toString()))),
                user
            )
        }

        val anonymous = requiresCoupon("", null)["data"]["_entities"][0]["discountRequiresCoupon"]
        assertThat(anonymous["totalCount"].asInt()).isEqualTo(0)
        assertThat(anonymous["nodes"].size()).isEqualTo(0)
        val own = requiresCoupon("", buyer)["data"]["_entities"][0]["discountRequiresCoupon"]
        assertThat(own["nodes"].toList().map { it["id"].asString() }).containsExactly(couponA.toString())
        val all = requiresCoupon("(orderBy: {field: VALID_FROM})", employee)["data"]["_entities"][0]["discountRequiresCoupon"]
        assertThat(all["nodes"].toList().map { it["id"].asString() }).containsExactly(couponB.toString(), couponA.toString())
        val filtered = requiresCoupon("(filter: {userHasCoupon: \"$otherId\"})", employee)["data"]["_entities"][0]["discountRequiresCoupon"]
        assertThat(filtered["nodes"].toList().map { it["id"].asString() }).containsExactly(couponB.toString())
        assertThat(errorMessage(requiresCoupon("(filter: {userHasCoupon: \"$otherId\"})", null)))
            .endsWith("The userHasCoupon filter requires an authorized user")
        assertThat(errorMessage(requiresCoupon("(filter: {userHasCoupon: \"$otherId\"})", buyer)))
            .contains("Unauthorized access: $buyerId is not an employee or admin")

        fun userCoupons(userId: UUID, user: String) = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on User { coupons { totalCount nodes { id code } } } } }
            """.trimIndent(),
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))),
            user
        )
        val ownCoupons = userCoupons(buyerId, buyer)["data"]["_entities"][0]["coupons"]
        assertThat(ownCoupons["totalCount"].asInt()).isEqualTo(1)
        assertThat(ownCoupons["nodes"][0]["id"].asString()).isEqualTo(couponA.toString())
        assertThat(errorMessage(userCoupons(otherId, buyer))).contains("is not an employee or admin")
        assertThat(userCoupons(otherId, employee)["data"]["_entities"][0]["coupons"]["nodes"][0]["id"].asString())
            .isEqualTo(couponB.toString())

        val users = graphql(
            """{ coupon(id: "$couponA") { users(orderBy: {field: ID, direction: DESC}) { totalCount nodes { id } } } }""",
            user = employee
        )["data"]["coupon"]["users"]
        assertThat(users["totalCount"].asInt()).isEqualTo(1)
        assertThat(users["nodes"][0]["id"].asString()).isEqualTo(buyerId.toString())
    }

    @Test
    fun `discount usage connections and entities`() {
        val discountId = createDiscount(maxUsagesPerUser = 100, categoryIds = listOf(registerCategory()))
        val userA = registerUser()
        val userB = registerUser()
        execute("INSERT INTO DiscountUsageEntity (discountId, userId, usages) VALUES ('$discountId', '$userA', 7), ('$discountId', '$userB', 3)")
        val usages = graphql(
            """{ discount(id: "$discountId") { discountUsages(orderBy: {field: USAGES}) { totalCount nodes { id usages user { id } discount { id } } } } }""",
            user = employee
        )["data"]["discount"]["discountUsages"]
        assertThat(usages["totalCount"].asInt()).isEqualTo(2)
        assertThat(usages["nodes"].toList().map { it["usages"].asInt() }).containsExactly(3, 7)
        assertThat(usages["nodes"][0]["user"]["id"].asString()).isEqualTo(userB.toString())
        val usageId = usages["nodes"][0]["id"].asString()

        val entities = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on DiscountUsage { id usages discount { id } }
              ... on User { id discountUsages { totalCount nodes { usages } } } } }
            """.trimIndent(),
            mapOf("r" to listOf(
                mapOf("__typename" to "DiscountUsage", "id" to usageId),
                mapOf("__typename" to "User", "id" to userA.toString())
            )),
            employee
        )["data"]["_entities"]
        assertThat(entities[0]["usages"].asInt()).isEqualTo(3)
        assertThat(entities[0]["discount"]["id"].asString()).isEqualTo(discountId.toString())
        assertThat(entities[1]["discountUsages"]["nodes"][0]["usages"].asInt()).isEqualTo(7)
    }

    @Test
    fun `entities of all key types resolve, stubs without a database check`() {
        val discountId = createDiscount(categoryIds = listOf(registerCategory()))
        val couponId = createCoupon(discountId)
        val stubId = UUID.randomUUID().toString()
        val result = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              __typename
              ... on Category { id } ... on Product { id } ... on ProductVariant { id } ... on User { id }
              ... on Discount { id discount } ... on Coupon { id code } } }
            """.trimIndent(),
            mapOf("r" to listOf("Category", "Product", "ProductVariant", "User").map { mapOf("__typename" to it, "id" to stubId) } +
                listOf(mapOf("__typename" to "Discount", "id" to discountId.toString()), mapOf("__typename" to "Coupon", "id" to couponId.toString()))),
        )
        assertThat(result["errors"]).isNull()
        val entities = result["data"]["_entities"]
        assertThat((0..3).map { entities[it]["id"].asString() }).containsOnly(stubId)
        assertThat(entities[4]["discount"].asDouble()).isEqualTo(0.1)
        assertThat(entities[5]["id"].asString()).isEqualTo(couponId.toString())

        // FIXED (Q4): an unknown id of a data-loaded type fails only its own entity (was: the whole batch, NPE)
        val unknown = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on Discount { id } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "Discount", "id" to discountId.toString()), mapOf("__typename" to "Discount", "id" to stubId)))
        )
        assertThat(unknown["errors"]).hasSize(1)
        assertThat(unknown["data"]["_entities"][0]["id"].asString()).isEqualTo(discountId.toString())
        assertThat(unknown["data"]["_entities"][1].isNull).isTrue()
    }

    // ----------------------------------------------------------------------------------------- applicable discounts

    @Test
    fun `applicableDiscounts of a product variant respect targets, coupons, validity and the user`() {
        val categoryId = registerCategory()
        val productId = registerProduct(listOf(categoryId))
        val variantId = registerVariant(productId)
        val viaCategory = createDiscount(categoryIds = listOf(categoryId))
        val viaProduct = createDiscount(productIds = listOf(productId))
        val viaVariant = createDiscount(variantIds = listOf(variantId))
        createDiscount(variantIds = listOf(variantId), validUntil = "2021-01-01T00:00:00Z") // expired
        createDiscount(variantIds = listOf(registerVariant(productId))) // other variant
        val withCoupon = createDiscount(variantIds = listOf(variantId))
        val couponId = createCoupon(withCoupon)
        val buyerId = registerUser()
        val stranger = authorizedUserHeader(registerUser(), "buyer")
        val buyer = authorizedUserHeader(buyerId, "buyer")

        fun applicable(user: String?, args: String = "") = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on ProductVariant { applicableDiscounts$args { totalCount hasNextPage nodes { id } } } } }
            """.trimIndent(),
            mapOf("r" to listOf(mapOf("__typename" to "ProductVariant", "id" to variantId.toString()))),
            user
        )["data"]["_entities"][0]["applicableDiscounts"]

        val expected = listOf(viaCategory, viaProduct, viaVariant).map { it.toString() }.sorted()
        assertThat(applicable(null)["nodes"].toList().map { it["id"].asString() }).containsExactlyElementsOf(expected)
        assertThat(applicable(stranger)["totalCount"].asInt()).isEqualTo(3)
        assertThat(registerCoupon(couponCode(couponId), buyerId, employee)["errors"]).isNull()
        val withUser = applicable(buyer)
        assertThat(withUser["nodes"].toList().map { it["id"].asString() })
            .containsExactlyElementsOf((expected + withCoupon.toString()).sorted())
        val paged = applicable(buyer, "(first: 2, orderBy: {direction: DESC})")
        assertThat(paged["hasNextPage"].asBoolean()).isTrue()
        assertThat(paged["nodes"].toList().map { it["id"].asString() })
            .containsExactlyElementsOf((expected + withCoupon.toString()).sortedDescending().take(2))
    }

    @Test
    fun `findApplicableDiscounts computes discounts per variant like the original`() {
        val categoryId = registerCategory()
        val productId = registerProduct(listOf(categoryId))
        val variantA = registerVariant(productId)
        val variantB = registerVariant(registerProduct())
        val userId = registerUser()
        val limited = createDiscount(maxUsagesPerUser = 5, variantIds = listOf(variantA, variantB))
        val generous = createDiscount(maxUsagesPerUser = 100, categoryIds = listOf(categoryId))
        val minAmount = createDiscount(maxUsagesPerUser = 100, minOrderAmount = 1000, productIds = listOf(productId))
        val couponDiscount = createDiscount(maxUsagesPerUser = 100, variantIds = listOf(variantB))
        val couponId = createCoupon(couponDiscount)
        assertThat(registerCoupon(couponCode(couponId), userId)["errors"]).isNull()
        // existing usage of the limited discount: 1 of 5 used
        execute("INSERT INTO DiscountUsageEntity (discountId, userId, usages) VALUES ('$limited', '$userId', 1)")

        fun find(orderAmount: Int, items: List<Triple<UUID, Int, List<UUID>>>) = graphql(
            """
            query(${'$'}input: FindApplicableDiscountsInput!) {
              findApplicableDiscounts(input: ${'$'}input) { productVariantId count discounts { id discount } } }
            """.trimIndent(),
            mapOf("input" to mapOf(
                "userId" to userId.toString(), "orderAmount" to orderAmount,
                "productVariants" to items.map { (variant, count, coupons) ->
                    mapOf("productVariantId" to variant.toString(), "count" to count, "couponIds" to coupons.map { it.toString() })
                }
            ))
        )

        val result = find(500, listOf(Triple(variantA, 3, emptyList()), Triple(variantB, 2, listOf(couponId))))
        assertThat(result["errors"]).withFailMessage { result.toString() }.isNull()
        val perVariant = result["data"]["findApplicableDiscounts"]
        assertThat(perVariant[0]["productVariantId"].asString()).isEqualTo(variantA.toString())
        assertThat(perVariant[0]["count"].asInt()).isEqualTo(3)
        // limited: 4 remaining >= 3 -> usable (then 1 left), generous via category; minAmount: 500 < 1000 -> no
        assertThat(perVariant[0]["discounts"].toList().map { it["id"].asString() })
            .containsExactlyInAnyOrder(limited.toString(), generous.toString())
        // variant B: limited has only 1 remaining < 2 -> not usable; coupon discount applies (coupon passed + redeemed)
        assertThat(perVariant[1]["discounts"].toList().map { it["id"].asString() }).containsExactly(couponDiscount.toString())

        val withAmount = find(1000, listOf(Triple(variantA, 1, emptyList())))["data"]["findApplicableDiscounts"][0]["discounts"]
        assertThat(withAmount.toList().map { it["id"].asString() })
            .containsExactlyInAnyOrder(limited.toString(), generous.toString(), minAmount.toString())

        // coupon not passed -> the coupon discount does not apply
        val noCoupon = find(500, listOf(Triple(variantB, 1, emptyList())))["data"]["findApplicableDiscounts"][0]["discounts"]
        assertThat(noCoupon.toList().map { it["id"].asString() }).containsExactly(limited.toString())

        // errors
        val unknownVariant = UUID.randomUUID()
        assertThat(errorMessage(find(1, listOf(Triple(unknownVariant, 1, emptyList())))))
            .isEqualTo("Exception while fetching data (/findApplicableDiscounts) : Product variant(s) with id(s) [$unknownVariant] do(es) not exist")
        val unknownCoupon = UUID.randomUUID()
        assertThat(errorMessage(find(1, listOf(Triple(variantA, 1, listOf(unknownCoupon))))))
            .endsWith("Coupon(s) with id(s) [$unknownCoupon] do(es) not exist")
        assertThat(errorMessage(find(1, listOf(Triple(variantA, 1, listOf(couponId))))))
            .endsWith("Coupon with id $couponId could not be used, either due to insufficient remaining usages, the user not owning the coupon, or the coupon not being applicable")
        assertThat(errorMessage(find(1, listOf(Triple(variantB, 1, listOf(couponId, couponId))))))
            .endsWith("Coupons with ids [$couponId, $couponId] are used multiple times for the same discount $couponDiscount")

        // FIXED (Q3): a discount without per-user limit is unlimited (the original failed with an NPE)
        graphql("""mutation { updateDiscount(input: {id: "$generous", maxUsagesPerUser: null}) { id } }""", user = employee)
        val unlimited = find(500, listOf(Triple(variantA, 1000, emptyList())))
        assertThat(unlimited["errors"]).isNull()
        assertThat(unlimited["data"]["findApplicableDiscounts"][0]["discounts"].toList().map { it["id"].asString() }).contains(generous.toString())
        // a variant without any discount still works
        val plain = registerVariant(registerProduct())
        assertThat(find(500, listOf(Triple(plain, 1, emptyList())))["data"]["findApplicableDiscounts"][0]["discounts"].size()).isEqualTo(0)
    }
}
