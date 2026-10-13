package org.misarch.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Behaviour of the catalog service against a real Postgres (Testcontainers) and a fake Dapr sidecar:
 * subscriptions, mutations + published events (exact JSON), queries/connections, authorization, federation.
 * The database is shared by all tests of the JVM, so every test creates its own data (random ids/names).
 */
class CatalogIntegrationTest : MisarchIntegrationTest() {

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var applicationContext: org.springframework.context.ApplicationContext

    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")
    private val admin = authorizedUserHeader(UUID.randomUUID(), "admin")
    private val buyerId = UUID.randomUUID()
    private val buyer = authorizedUserHeader(buyerId, "buyer")

    // ---------------------------------------------------------------- helpers

    private fun registerTaxRate(id: UUID = UUID.randomUUID()): UUID {
        assertThat(deliverEvent("/subscription/tax/tax-rate/created", "tax/tax-rate/created", """{"id":"$id"}"""))
            .isEqualTo(200)
        return id
    }

    private fun registerMedia(id: UUID = UUID.randomUUID()): UUID {
        assertThat(deliverEvent("/subscription/media/media/created", "media/media/created", """{"id":"$id"}"""))
            .isEqualTo(200)
        return id
    }

    private fun JsonNode.ok(): JsonNode {
        assertThat(this["errors"]).describedAs("errors: %s", this["errors"]).isNull()
        return this["data"]
    }

    private fun JsonNode.errorMessage(): String {
        assertThat(this["errors"]).describedAs("expected errors in %s", this).isNotNull()
        return this["errors"][0]["message"].asString()
    }

    private data class CategoryIds(val id: UUID, val categorical: UUID, val numerical: UUID)

    private fun createCategory(name: String = "cat-" + UUID.randomUUID()): CategoryIds {
        val data = graphql(
            """
            mutation { createCategory(input: { name: "$name", description: "desc of $name",
              categoricalCharacteristics: [{ name: "Color", description: "the color" }],
              numericalCharacteristics: [{ name: "Memory", description: "the memory", unit: "GB" }] })
              { id characteristics { nodes { __typename id name } } } }
            """.trimIndent(), user = employee
        ).ok()["createCategory"]
        val nodes = data["characteristics"]["nodes"].toList()
        return CategoryIds(
            UUID.fromString(data["id"].asString()),
            UUID.fromString(nodes.single { it["__typename"].asString() == "CategoricalCategoryCharacteristic" }["id"].asString()),
            UUID.fromString(nodes.single { it["__typename"].asString() == "NumericalCategoryCharacteristic" }["id"].asString()),
        )
    }

    private data class ProductIds(val id: UUID, val variant: UUID, val version: UUID)

    private fun versionInput(
        taxRate: UUID,
        mediaIds: List<UUID> = emptyList(),
        categorical: List<Pair<UUID, String>> = emptyList(),
        numerical: List<Pair<UUID, Double>> = emptyList(),
        name: String = "V1",
        canBeReturnedForDays: Int? = 30,
    ): String = """
        { name: "$name", description: "D of $name", retailPrice: 20, ${if (canBeReturnedForDays != null) "canBeReturnedForDays: $canBeReturnedForDays," else ""}
          weight: 0.5, taxRateId: "$taxRate", mediaIds: [${mediaIds.joinToString(",") { "\"$it\"" }}],
          categoricalCharacteristicValues: [${categorical.joinToString(",") { "{characteristicId: \"${it.first}\", value: \"${it.second}\"}" }}],
          numericalCharacteristicValues: [${numerical.joinToString(",") { "{characteristicId: \"${it.first}\", value: ${it.second}}" }}] }
    """.trimIndent()

    private fun createProductRaw(
        categoryIds: List<UUID>, versionInput: String, internalName: String = "p-" + UUID.randomUUID(),
        isPublic: Boolean = true, variantPublic: Boolean = true, user: String? = employee,
    ): JsonNode = graphql(
        """
        mutation { createProduct(input: { internalName: "$internalName", isPubliclyVisible: $isPublic,
          categoryIds: [${categoryIds.joinToString(",") { "\"$it\"" }}],
          defaultVariant: { isPubliclyVisible: $variantPublic, initialVersion: $versionInput } })
          { id internalName isPubliclyVisible defaultVariant { id isPubliclyVisible currentVersion { id version } } } }
        """.trimIndent(), user = user
    )

    private fun createProduct(
        category: CategoryIds, taxRate: UUID, internalName: String = "p-" + UUID.randomUUID(),
        isPublic: Boolean = true, variantPublic: Boolean = true, value: String = "red",
    ): ProductIds {
        val data = createProductRaw(
            listOf(category.id),
            versionInput(taxRate, categorical = listOf(category.categorical to value), numerical = listOf(category.numerical to 8.0)),
            internalName, isPublic, variantPublic
        ).ok()["createProduct"]
        return ProductIds(
            UUID.fromString(data["id"].asString()),
            UUID.fromString(data["defaultVariant"]["id"].asString()),
            UUID.fromString(data["defaultVariant"]["currentVersion"]["id"].asString()),
        )
    }

    private fun categoryProductsTotal(categoryId: UUID): Int = graphql(
        """{ category(id: "$categoryId") { products { totalCount } } }""", user = employee
    ).ok()["category"]["products"]["totalCount"].asInt()

    private fun entities(typename: String, ids: List<Any>, selection: String, user: String? = null): JsonNode = graphql(
        """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on $typename { $selection } } }""",
        mapOf("r" to ids.map { mapOf("__typename" to typename, "id" to it.toString()) }),
        user
    )

    // ---------------------------------------------------------------- infrastructure endpoints

    @Test
    fun `dapr subscribe equals the original's subscription list`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val referenceDir = File(System.getProperty("misarch.reference.dir") ?: "../../reference")
        val reference = File(referenceDir, "dapr-subscribe/catalog.json").readText().trim()
        assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree(reference))
        assertThat(body).isEqualTo(reference)
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", org.springframework.test.json.JsonCompareMode.STRICT)
    }

    @Test
    fun `flyway applies the original migrations unchanged`() {
        val databaseClient = applicationContext.getBean(org.springframework.r2dbc.core.DatabaseClient::class.java)
        // the migration files are byte-identical copies of the original's, so a database migrated by the original
        // has the same versions and checksums in flyway_schema_history (no re-run, no validation error)
        val versions = databaseClient.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
            .map { row -> row.get("version", String::class.java)!! }
            .all().collectList().block()!!
        assertThat(versions).containsExactly("1.1", "1.2", "1.3", "1.4", "1.5")
        val columns = databaseClient.sql(
            "SELECT table_name, column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' " +
                "AND table_name LIKE '%entity' ORDER BY table_name, column_name"
        ).map { row -> "${row.get(0)}.${row.get(1)}:${row.get(2)}" }.all().collectList().block()!!
        assertThat(columns).contains(
            "productvariantversionentity.canbereturnedfordays:integer",
            "productvariantversionentity.weight:double precision",
            "productvariantversionentity.createdat:timestamp with time zone",
            "categorycharacteristicvalueentity.stringvalue:character varying",
            "productvariantversiontomediaentity.mediaid:uuid",
            "taxrateentity.id:uuid",
        )
        val trigger = databaseClient.sql(
            "SELECT count(*) FROM information_schema.triggers WHERE trigger_name = 'trg_check_category_value_product_category'"
        ).map { row -> (row.get(0) as Number).toInt() }.one().block()!!
        assertThat(trigger).isGreaterThan(0)
    }

    // ---------------------------------------------------------------- subscriptions

    @Test
    fun `tax rate and media events are registered, duplicates are acknowledged (FIXED), extra fields are ignored`() {
        val taxRate = UUID.randomUUID()
        assertThat(
            deliverEvent(
                "/subscription/tax/tax-rate/created", "tax/tax-rate/created",
                """{"id":"$taxRate","name":"VAT","description":"x","currentVersion":1}"""
            )
        ).isEqualTo(200)
        assertThat(deliverEvent("/subscription/tax/tax-rate/created", "tax/tax-rate/created", """{"id":"$taxRate"}"""))
            .isEqualTo(200)
        val media = registerMedia()
        assertThat(deliverEvent("/subscription/media/media/created", "media/media/created", """{"id":"$media"}"""))
            .isEqualTo(200)

        // usable as references afterwards
        val category = createCategory()
        val product = createProductRaw(listOf(category.id), versionInput(taxRate, mediaIds = listOf(media))).ok()
        assertThat(product["createProduct"]["id"]).isNotNull()
        assertThat(dapr.published).hasSize(4) // category + 3 product events
    }

    // ---------------------------------------------------------------- categories

    @Test
    fun `employee creates a category with characteristics, event is published`() {
        val name = "cat-" + UUID.randomUUID()
        val data = graphql(
            """
            mutation { createCategory(input: { name: "$name", description: "CDs",
              categoricalCharacteristics: { name: "Pop", description: "Pop" },
              numericalCharacteristics: [{ name: "Length", description: "Length", unit: "min" }] })
              { id name description characteristics(orderBy: {field: ID, direction: DESC}) { totalCount hasNextPage nodes {
                __typename id name description category { id } ... on NumericalCategoryCharacteristic { unit } } } } }
            """.trimIndent(), user = admin
        ).ok()["createCategory"]
        val id = data["id"].asString()
        assertThat(data["name"].asString()).isEqualTo(name)
        assertThat(data["characteristics"]["totalCount"].asInt()).isEqualTo(2)
        assertThat(data["characteristics"]["hasNextPage"].asBoolean()).isFalse()
        val nodes = data["characteristics"]["nodes"].toList()
        assertThat(nodes.map { it["id"].asString() }).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(nodes.map { it["category"]["id"].asString() }).containsOnly(id)
        assertThat(nodes.single { it["__typename"].asString() == "NumericalCategoryCharacteristic" }["unit"].asString())
            .isEqualTo("min")

        val events = dapr.awaitPublished("catalog/category/created")
        assertThat(events).hasSize(1)
        assertThat(events[0].pubsubName).isEqualTo("pubsub")
        assertThat(events[0].contentType).startsWith("application/json")
        assertThat(events[0].body).isEqualTo("""{"id":"$id","name":"$name","description":"CDs"}""")
        assertThat(dapr.published).hasSize(1)

        // category(id), categories connection, categoryCharacteristic(id) with the concrete type
        assertThat(graphql("""{ category(id: "$id") { name } }""").ok()["category"]["name"].asString()).isEqualTo(name)
        val numericalId = nodes.single { it["__typename"].asString() == "NumericalCategoryCharacteristic" }["id"].asString()
        val characteristic = graphql(
            """{ categoryCharacteristic(id: "$numericalId") { __typename id name ... on NumericalCategoryCharacteristic { unit } } }"""
        ).ok()["categoryCharacteristic"]
        assertThat(characteristic["__typename"].asString()).isEqualTo("NumericalCategoryCharacteristic")
        assertThat(characteristic["unit"].asString()).isEqualTo("min")
    }

    @Test
    fun `createCategory with duplicate characteristic names fails and rolls back`() {
        val name = "dup-" + UUID.randomUUID()
        val result = graphql(
            """
            mutation { createCategory(input: { name: "$name", description: "d",
              categoricalCharacteristics: [{ name: "A", description: "a" }, { name: "B", description: "b" }],
              numericalCharacteristics: [{ name: "A", description: "a", unit: "u" }] }) { id } }
            """.trimIndent(), user = employee
        )
        assertThat(result.errorMessage())
            .isEqualTo("Exception while fetching data (/createCategory) : Characteristic names must be unique. Duplicates: [A]")
        val all = graphql("{ categories { nodes { name } } }").ok()["categories"]["nodes"].toList()
        assertThat(all.map { it["name"].asString() }).doesNotContain(name)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `mutations require an employee`() {
        val anonymous = graphql("""mutation { createCategory(input: { name: "x", description: "x", categoricalCharacteristics: [], numericalCharacteristics: [] }) { id } }""")
        assertThat(anonymous.errorMessage()).isEqualTo("Exception while fetching data (/createCategory) : Unauthorized access")
        val asBuyer = graphql(
            """mutation { updateProductVariant(input: { id: "${UUID.randomUUID()}", isPubliclyVisible: true }) { id } }""",
            user = buyer
        )
        assertThat(asBuyer.errorMessage())
            .isEqualTo("Exception while fetching data (/updateProductVariant) : Unauthorized access: $buyerId is not an employee or admin")
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `characteristics can be added to existing categories`() {
        val category = createCategory()
        val categorical = graphql(
            """mutation { createCategoricalCategoryCharacteristic(input: { name: "Style", description: "s", categoryId: "${category.id}" }) { __typename id name category { id } } }""",
            user = employee
        ).ok()["createCategoricalCategoryCharacteristic"]
        assertThat(categorical["__typename"].asString()).isEqualTo("CategoricalCategoryCharacteristic")
        assertThat(categorical["category"]["id"].asString()).isEqualTo(category.id.toString())
        val numerical = graphql(
            """mutation { createNumericalCategoryCharacteristic(input: { name: "Weight", description: "w", unit: "kg", categoryId: "${category.id}" }) { id unit } }""",
            user = employee
        ).ok()["createNumericalCategoryCharacteristic"]
        assertThat(numerical["unit"].asString()).isEqualTo("kg")
        val count = graphql("""{ category(id: "${category.id}") { characteristics(first: 1) { totalCount hasNextPage nodes { id } } } }""")
            .ok()["category"]["characteristics"]
        assertThat(count["totalCount"].asInt()).isEqualTo(4)
        assertThat(count["hasNextPage"].asBoolean()).isTrue()
        assertThat(count["nodes"].size()).isEqualTo(1)

        val unknown = UUID.randomUUID()
        val result = graphql(
            """mutation { createNumericalCategoryCharacteristic(input: { name: "W", description: "w", unit: "kg", categoryId: "$unknown" }) { id } }""",
            user = employee
        )
        assertThat(result.errorMessage())
            .isEqualTo("Exception while fetching data (/createNumericalCategoryCharacteristic) : Category with id $unknown does not exist.")
        assertThat(dapr.published).hasSize(1) // only the category created by createCategory
    }

    // ---------------------------------------------------------------- products

    @Test
    fun `createProduct creates product, default variant and version and publishes three events`() {
        val taxRate = registerTaxRate()
        val m1 = registerMedia()
        val m2 = registerMedia()
        val category = createCategory()
        dapr.reset()
        val internalName = "POP-" + UUID.randomUUID()
        val result = graphql(
            """
            mutation { createProduct(input: { internalName: "$internalName", isPubliclyVisible: true, categoryIds: ["${category.id}"],
              defaultVariant: { isPubliclyVisible: true, initialVersion: ${versionInput(taxRate, listOf(m1, m2, m1), listOf(category.categorical to "CDs"), listOf(category.numerical to 8.0), "POP 2025")} } })
              { id internalName isPubliclyVisible categories { totalCount nodes { id } }
                defaultVariant { id isPubliclyVisible product { id }
                  currentVersion { id name description version retailPrice createdAt canBeReturnedForDays weight taxRate { id }
                    productVariant { id }
                    medias { totalCount nodes { id } }
                    characteristicValues { totalCount nodes { __typename characteristic { id }
                      ... on CategoricalCategoryCharacteristicValue { text: value } ... on NumericalCategoryCharacteristicValue { number: value } } } } } } }
            """.trimIndent(), user = employee
        ).ok()["createProduct"]
        val productId = result["id"].asString()
        val variant = result["defaultVariant"]
        val variantId = variant["id"].asString()
        val version = variant["currentVersion"]
        val versionId = version["id"].asString()
        assertThat(result["internalName"].asString()).isEqualTo(internalName)
        assertThat(result["categories"]["nodes"][0]["id"].asString()).isEqualTo(category.id.toString())
        assertThat(variant["product"]["id"].asString()).isEqualTo(productId)
        assertThat(version["version"].asInt()).isEqualTo(1)
        assertThat(version["retailPrice"].asInt()).isEqualTo(20)
        assertThat(version["canBeReturnedForDays"].asInt()).isEqualTo(30)
        assertThat(version["weight"].asDouble()).isEqualTo(0.5)
        assertThat(version["taxRate"]["id"].asString()).isEqualTo(taxRate.toString())
        assertThat(version["productVariant"]["id"].asString()).isEqualTo(variantId)
        assertThat(version["createdAt"].asString()).matches("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}(Z|[+-]\d\d:\d\d)""")
        assertThat(version["medias"]["totalCount"].asInt()).isEqualTo(2)
        assertThat(version["medias"]["nodes"].toList().map { it["id"].asString() })
            .containsExactly(*listOf(m1, m2).map { it.toString() }.sorted().toTypedArray())
        val values = version["characteristicValues"]["nodes"].toList()
        assertThat(values).hasSize(2)
        assertThat(values.single { it["__typename"].asString() == "CategoricalCategoryCharacteristicValue" }["text"].asString())
            .isEqualTo("CDs")
        val numerical = values.single { it["__typename"].asString() == "NumericalCategoryCharacteristicValue" }
        assertThat(numerical["number"].asDouble()).isEqualTo(8.0)
        assertThat(numerical["characteristic"]["id"].asString()).isEqualTo(category.numerical.toString())

        // events: order and exact JSON (Dapr SDK serializer: `isPubliclyVisible` -> `publiclyVisible`, nulls omitted)
        assertThat(dapr.published.map { it.topic }).containsExactly(
            "catalog/product/created", "catalog/product-variant/created", "catalog/product-variant-version/created"
        )
        assertThat(dapr.published[0].body).isEqualTo(
            """{"id":"$productId","internalName":"$internalName","defaultVariantId":"$variantId","categoryIds":["${category.id}"],"publiclyVisible":true}"""
        )
        assertThat(dapr.published[1].body).isEqualTo(
            """{"id":"$variantId","productId":"$productId","currentVersionId":"$versionId","publiclyVisible":true}"""
        )
        val versionEvent = dapr.published[2].body
        val createdAt = dapr.published[2].json["createdAt"].asString()
        assertThat(OffsetDateTime.parse(createdAt).toInstant().toEpochMilli())
            .isEqualTo(OffsetDateTime.parse(version["createdAt"].asString()).toInstant().toEpochMilli())
        assertThat(versionEvent).isEqualTo(
            """{"id":"$versionId","name":"POP 2025","description":"D of POP 2025","version":1,"retailPrice":20,"createdAt":"$createdAt","canBeReturnedForDays":30,"productVariantId":"$variantId","taxRateId":"$taxRate","weight":0.5,"mediaIds":["$m1","$m2"]}"""
        )
    }

    @Test
    fun `createProduct validation errors roll back and publish nothing`() {
        val taxRate = registerTaxRate()
        val category = createCategory()
        val other = createCategory()
        dapr.reset()
        fun assertFails(result: JsonNode, message: String) {
            assertThat(result.errorMessage()).isEqualTo("Exception while fetching data (/createProduct) : $message")
        }
        val unknownCategory = UUID.randomUUID()
        assertFails(
            createProductRaw(listOf(category.id, unknownCategory), versionInput(taxRate)),
            "Category with id $unknownCategory does not exist."
        )
        val unknownTaxRate = UUID.randomUUID()
        assertFails(
            createProductRaw(listOf(category.id), versionInput(unknownTaxRate)),
            "Tax rate with id $unknownTaxRate does not exist"
        )
        val unknownMedia = UUID.randomUUID()
        assertFails(
            createProductRaw(listOf(category.id), versionInput(taxRate, mediaIds = listOf(unknownMedia))),
            "Media with id $unknownMedia does not exist."
        )
        assertFails(
            createProductRaw(
                listOf(category.id),
                versionInput(taxRate, categorical = listOf(category.categorical to "a", category.categorical to "b"))
            ),
            "Duplicate characteristic ids: [${category.categorical}]"
        )
        assertFails(
            createProductRaw(listOf(category.id), versionInput(taxRate, categorical = listOf(other.categorical to "a"))),
            "Invalid characteristic ids which cannot be used here: [${other.categorical}]"
        )
        assertFails(
            createProductRaw(listOf(category.id), versionInput(taxRate, numerical = listOf(category.categorical to 1.0))),
            "Characteristic id ${category.categorical} is not valid for this type of characteristic."
        )
        assertThat(categoryProductsTotal(category.id)).isEqualTo(0)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `createProduct ignores duplicate category ids (FIXED)`() {
        val taxRate = registerTaxRate()
        val category = createCategory()
        dapr.reset()
        val product = createProductRaw(listOf(category.id, category.id), versionInput(taxRate)).ok()["createProduct"]
        val productId = product["id"].asString()
        assertThat(categoryProductsTotal(category.id)).isEqualTo(1)
        val categories = graphql("""{ product(id: "$productId") { categories { totalCount } } }""", user = employee)
            .ok()["product"]["categories"]["totalCount"].asInt()
        assertThat(categories).isEqualTo(1)
        assertThat(dapr.published("catalog/product/created").single().json["categoryIds"].toList().map { it.asString() })
            .containsExactly(category.id.toString())
    }

    @Test
    fun `concurrent createProductVariantVersion calls get distinct version numbers (FIXED)`() {
        val taxRate = registerTaxRate()
        val category = createCategory()
        val product = createProduct(category, taxRate)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val versions = (1..6).map { i ->
                pool.submit<Int> {
                    graphql(
                        """mutation { createProductVariantVersion(input: { productVariantId: "${product.variant}", name: "C$i",
                             description: "d", retailPrice: 10, weight: 1, taxRateId: "$taxRate", mediaIds: [],
                             categoricalCharacteristicValues: [], numericalCharacteristicValues: [] }) { version } }""",
                        user = employee
                    ).ok()["createProductVariantVersion"]["version"].asInt()
                }
            }.map { it.get() }
            assertThat(versions).containsExactlyInAnyOrder(2, 3, 4, 5, 6, 7)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `failing publish fails the mutation and rolls back`() {
        val taxRate = registerTaxRate()
        val category = createCategory()
        dapr.publishStatus = 500
        val result = createProductRaw(listOf(category.id), versionInput(taxRate))
        assertThat(result["errors"]).isNotNull()
        dapr.publishStatus = 204
        assertThat(categoryProductsTotal(category.id)).isEqualTo(0)
    }

    @Test
    fun `createProductVariant and createProductVariantVersion`() {
        val taxRate = registerTaxRate()
        val media = registerMedia()
        val category = createCategory()
        val product = createProduct(category, taxRate)
        dapr.reset()

        val variant = graphql(
            """mutation { createProductVariant(input: { productId: "${product.id}", isPubliclyVisible: false,
                 initialVersion: ${versionInput(taxRate, canBeReturnedForDays = null, name = "VX")} })
                 { id isPubliclyVisible product { id } currentVersion { id version canBeReturnedForDays } } }""",
            user = employee
        ).ok()["createProductVariant"]
        val variantId = variant["id"].asString()
        val firstVersion = variant["currentVersion"]["id"].asString()
        assertThat(variant["currentVersion"]["version"].asInt()).isEqualTo(1)
        assertThat(variant["currentVersion"]["canBeReturnedForDays"].isNull).isTrue()
        assertThat(dapr.published.map { it.topic })
            .containsExactly("catalog/product-variant/created", "catalog/product-variant-version/created")
        assertThat(dapr.published[0].body)
            .isEqualTo("""{"id":"$variantId","productId":"${product.id}","currentVersionId":"$firstVersion","publiclyVisible":false}""")
        assertThat(dapr.published[1].json.has("canBeReturnedForDays")).isFalse()
        assertThat(dapr.published[1].json["mediaIds"].size()).isEqualTo(0)
        dapr.reset()

        val second = graphql(
            """mutation { createProductVariantVersion(input: { productVariantId: "$variantId", name: "V2", description: "d2",
                 retailPrice: 25, weight: 1, taxRateId: "$taxRate", mediaIds: ["$media"], categoricalCharacteristicValues: [],
                 numericalCharacteristicValues: [{ characteristicId: "${category.numerical}", value: 16 }] })
                 { id version weight retailPrice productVariant { id currentVersion { id } } } }""",
            user = employee
        ).ok()["createProductVariantVersion"]
        assertThat(second["version"].asInt()).isEqualTo(2)
        assertThat(second["productVariant"]["currentVersion"]["id"].asString()).isEqualTo(second["id"].asString())
        // only the version event, no product-variant/updated although the current version changed (original behaviour)
        assertThat(dapr.published.map { it.topic }).containsExactly("catalog/product-variant-version/created")
        val event = dapr.published[0].json
        assertThat(event["version"].asInt()).isEqualTo(2)
        assertThat(dapr.published[0].body).contains(""""weight":1.0,"mediaIds":["$media"]}""")

        // versions (employee only) ordered by version
        val versions = graphql(
            """{ _entities(representations: [{__typename: "ProductVariant", id: "$variantId"}]) { ... on ProductVariant {
                 versions(orderBy: {field: VERSION, direction: DESC}) { totalCount nodes { version } } } } }""",
            user = employee
        ).ok()["_entities"][0]["versions"]
        assertThat(versions["totalCount"].asInt()).isEqualTo(2)
        assertThat(versions["nodes"].toList().map { it["version"].asInt() }).containsExactly(2, 1)
        val createdAtOrder = graphql(
            """{ _entities(representations: [{__typename: "ProductVariant", id: "$variantId"}]) { ... on ProductVariant {
                 versions(first: 1, orderBy: {field: CREATED_AT}) { hasNextPage nodes { version } } } } }""",
            user = admin
        ).ok()["_entities"][0]["versions"]
        assertThat(createdAtOrder["hasNextPage"].asBoolean()).isTrue()
        assertThat(createdAtOrder["nodes"][0]["version"].asInt()).isEqualTo(1)
        val asBuyer = graphql(
            """{ _entities(representations: [{__typename: "ProductVariant", id: "$variantId"}]) { ... on ProductVariant { versions { totalCount } } } }""",
            user = buyer
        )
        assertThat(asBuyer.errorMessage()).contains("Unauthorized access: $buyerId is not an employee or admin")

        val unknown = UUID.randomUUID()
        val noProduct = graphql(
            """mutation { createProductVariant(input: { productId: "$unknown", isPubliclyVisible: true, initialVersion: ${versionInput(taxRate)} }) { id } }""",
            user = employee
        )
        assertThat(noProduct.errorMessage())
            .isEqualTo("Exception while fetching data (/createProductVariant) : Product with id $unknown does not exist.")
        val noVariant = graphql(
            """mutation { createProductVariantVersion(input: { productVariantId: "$unknown", name: "x", description: "x", retailPrice: 1,
                 weight: 1, taxRateId: "$taxRate", mediaIds: [], categoricalCharacteristicValues: [], numericalCharacteristicValues: [] }) { id } }""",
            user = employee
        )
        assertThat(noVariant.errorMessage()).isEqualTo(
            "Exception while fetching data (/createProductVariantVersion) : Product variant with id $unknown does not exist."
        )
    }

    @Test
    fun `updateProduct and updateProductVariant publish update events`() {
        val taxRate = registerTaxRate()
        val category = createCategory()
        val product = createProduct(category, taxRate)
        val other = createProduct(category, taxRate)
        dapr.reset()

        val updated = graphql(
            """mutation { updateProduct(input: { id: "${product.id}", internalName: "renamed", isPubliclyVisible: false }) { id internalName isPubliclyVisible } }""",
            user = employee
        ).ok()["updateProduct"]
        assertThat(updated["internalName"].asString()).isEqualTo("renamed")
        assertThat(updated["isPubliclyVisible"].asBoolean()).isFalse()
        assertThat(dapr.published).hasSize(1)
        assertThat(dapr.published[0].topic).isEqualTo("catalog/product/updated")
        assertThat(dapr.published[0].body).isEqualTo(
            """{"id":"${product.id}","internalName":"renamed","defaultVariantId":"${product.variant}","categoryIds":["${category.id}"],"publiclyVisible":false}"""
        )

        // unchanged update still publishes
        graphql("""mutation { updateProduct(input: { id: "${product.id}" }) { id } }""", user = employee).ok()
        assertThat(dapr.published).hasSize(2)

        val wrongVariant = graphql(
            """mutation { updateProduct(input: { id: "${product.id}", defaultVariantId: "${other.variant}" }) { id } }""",
            user = employee
        )
        assertThat(wrongVariant.errorMessage()).isEqualTo(
            "Exception while fetching data (/updateProduct) : Variant with id ${other.variant} does not belong to product with id ${product.id}."
        )
        val unknown = UUID.randomUUID()
        assertThat(graphql("""mutation { updateProduct(input: { id: "$unknown" }) { id } }""", user = employee).errorMessage())
            .isEqualTo("Exception while fetching data (/updateProduct) : null")
        assertThat(dapr.published).hasSize(2)

        val variant = graphql(
            """mutation { updateProductVariant(input: { id: "${product.variant}", isPubliclyVisible: false }) { id isPubliclyVisible } }""",
            user = employee
        ).ok()["updateProductVariant"]
        assertThat(variant["isPubliclyVisible"].asBoolean()).isFalse()
        val variantEvents = dapr.awaitPublished("catalog/product-variant/updated")
        assertThat(variantEvents.single().body).isEqualTo("""{"id":"${product.variant}","publiclyVisible":false}""")
        assertThat(graphql("""mutation { updateProductVariant(input: { id: "$unknown" }) { id } }""", user = employee).errorMessage())
            .isEqualTo("Exception while fetching data (/updateProductVariant) : null")
    }

    // ---------------------------------------------------------------- queries and authorization

    @Test
    fun `paging arguments are validated like QueryDSL in the original`() {
        // observed on the running original (docs/INTEGRATION.md F2): QueryModifiers reject first 0 / negative values
        val first0 = graphql("""{ products(first: 0) { totalCount nodes { id } } }""", user = employee)
        assertThat(first0.has("data")).isFalse()
        assertThat(first0.errorMessage()).isEqualTo("Exception while fetching data (/products/nodes) : Limit must be greater than 0.")
        assertThat(graphql("""{ categories(first: 1, skip: -1) { nodes { id } } }""", user = employee).errorMessage())
            .isEqualTo("Exception while fetching data (/categories/nodes) : Offset must not be negative.")
        val firstNegative = graphql("""{ products(first: -1) { hasNextPage } }""", user = employee)
        assertThat(firstNegative.errorMessage())
            .isEqualTo("Exception while fetching data (/products/hasNextPage) : Offset must not be negative.")
    }

    @Test
    fun `product visibility, internalName and default variant authorization`() {
        val taxRate = registerTaxRate()
        val category = createCategory()
        val visible = createProduct(category, taxRate, internalName = "b-visible")
        val hidden = createProduct(category, taxRate, internalName = "a-hidden", isPublic = false)
        val hiddenVariant = createProduct(category, taxRate, internalName = "c-hidden-variant", variantPublic = false)

        // product(id)
        assertThat(graphql("""{ product(id: "${visible.id}") { id isPubliclyVisible } }""").ok()["product"]["id"].asString())
            .isEqualTo(visible.id.toString())
        assertThat(graphql("""{ product(id: "${hidden.id}") { id } }""", user = buyer).errorMessage())
            .isEqualTo("Exception while fetching data (/product) : Unauthorized access: $buyerId is not an employee or admin")
        assertThat(graphql("""{ product(id: "${hidden.id}") { id } }""").errorMessage())
            .isEqualTo("Exception while fetching data (/product) : Unauthorized access")
        assertThat(graphql("""{ product(id: "${hidden.id}") { id internalName } }""", user = employee).ok()["product"]["internalName"].asString())
            .isEqualTo("a-hidden")
        assertThat(graphql("""{ product(id: "${UUID.randomUUID()}") { id } }""", user = employee).errorMessage())
            .isEqualTo("Exception while fetching data (/product) : null")

        // internalName: employees only, also for public products
        assertThat(graphql("""{ product(id: "${visible.id}") { internalName } }""", user = buyer).errorMessage())
            .isEqualTo("Exception while fetching data (/product/internalName) : Unauthorized access: $buyerId is not an employee or admin")

        // defaultVariant / variants of a hidden product
        assertThat(graphql("""{ _entities(representations: [{__typename: "Product", id: "${hidden.id}"}]) { ... on Product { id isPubliclyVisible } } }""")
            .ok()["_entities"][0]["isPubliclyVisible"].asBoolean()).isFalse()
        assertThat(graphql("""{ _entities(representations: [{__typename: "Product", id: "${hidden.id}"}]) { ... on Product { defaultVariant { id } } } }""", user = buyer)
            .errorMessage()).contains("Unauthorized access: $buyerId is not an employee or admin")
        assertThat(graphql("""{ _entities(representations: [{__typename: "Product", id: "${hidden.id}"}]) { ... on Product { variants { totalCount } } } }""")
            .errorMessage()).isEqualTo("Exception while fetching data (/_entities[0]/variants) : Unauthorized access")

        // hidden variants are filtered for buyers; currentVersion of a hidden variant needs an employee
        val buyerVariants = graphql(
            """{ product(id: "${hiddenVariant.id}") { variants { totalCount hasNextPage nodes { id } } } }""", user = buyer
        ).ok()["product"]["variants"]
        assertThat(buyerVariants["totalCount"].asInt()).isEqualTo(0)
        val employeeVariants = graphql(
            """{ product(id: "${hiddenVariant.id}") { all: variants { totalCount } public: variants(filter: {isPubliclyVisible: true}) { totalCount }
                 hidden: variants(filter: {isPubliclyVisible: false}, first: 5) { totalCount hasNextPage nodes { id currentVersion { id } } } } }""",
            user = employee
        ).ok()["product"]
        assertThat(employeeVariants["all"]["totalCount"].asInt()).isEqualTo(1)
        assertThat(employeeVariants["public"]["totalCount"].asInt()).isEqualTo(0)
        assertThat(employeeVariants["hidden"]["nodes"][0]["currentVersion"]["id"].asString()).isEqualTo(hiddenVariant.version.toString())
        assertThat(graphql("""{ product(id: "${hiddenVariant.id}") { defaultVariant { currentVersion { id } } } }""", user = buyer)
            .errorMessage()).isEqualTo(
                "Exception while fetching data (/product/defaultVariant/currentVersion) : Unauthorized access: $buyerId is not an employee or admin"
            )
        // ProductVariant.product has no visibility check
        assertThat(graphql("""{ _entities(representations: [{__typename: "ProductVariant", id: "${hidden.variant}"}]) { ... on ProductVariant { product { id } } } }""")
            .ok()["_entities"][0]["product"]["id"].asString()).isEqualTo(hidden.id.toString())

        // Category.products: join + visibility filter + ordering
        val forBuyer = graphql(
            """{ category(id: "${category.id}") { products { totalCount nodes { id } } } }""", user = buyer
        ).ok()["category"]["products"]
        assertThat(forBuyer["totalCount"].asInt()).isEqualTo(2)
        assertThat(forBuyer["nodes"].toList().map { it["id"].asString() })
            .containsExactly(*listOf(visible.id, hiddenVariant.id).map { it.toString() }.sorted().toTypedArray())
        val forEmployee = graphql(
            """{ category(id: "${category.id}") { byName: products(orderBy: {field: INTERNAL_NAME, direction: DESC}) { totalCount nodes { internalName } }
                 page: products(first: 1, skip: 1, orderBy: {field: INTERNAL_NAME}) { totalCount hasNextPage nodes { internalName } }
                 last: products(first: 1, skip: 2, orderBy: {field: INTERNAL_NAME}) { hasNextPage nodes { internalName } }
                 hidden: products(filter: {isPubliclyVisible: false}) { totalCount nodes { id } } } }""",
            user = employee
        ).ok()["category"]
        assertThat(forEmployee["byName"]["nodes"].toList().map { it["internalName"].asString() })
            .containsExactly("c-hidden-variant", "b-visible", "a-hidden")
        assertThat(forEmployee["page"]["totalCount"].asInt()).isEqualTo(3)
        assertThat(forEmployee["page"]["hasNextPage"].asBoolean()).isTrue()
        assertThat(forEmployee["page"]["nodes"][0]["internalName"].asString()).isEqualTo("b-visible")
        assertThat(forEmployee["last"]["hasNextPage"].asBoolean()).isFalse()
        assertThat(forEmployee["hidden"]["nodes"].toList().map { it["id"].asString() }).containsExactly(hidden.id.toString())

        // Query.products: buyers only see public products
        val buyerProducts = graphql("""{ products(filter: {isPubliclyVisible: false}) { totalCount nodes { id } } }""", user = buyer)
            .ok()["products"]
        assertThat(buyerProducts["totalCount"].asInt()).isEqualTo(0)
        val employeeProducts = graphql("""{ products(filter: {isPubliclyVisible: false}, orderBy: {direction: DESC}) { totalCount nodes { id } } }""", user = employee)
            .ok()["products"]
        assertThat(employeeProducts["nodes"].toList().map { it["id"].asString() }).contains(hidden.id.toString())
        assertThat(employeeProducts["nodes"].toList().map { it["id"].asString() }).isSortedAccordingTo(Comparator.reverseOrder())
        val all = graphql("""{ products { totalCount } }""", user = employee).ok()["products"]["totalCount"].asInt()
        val public = graphql("""{ products { totalCount nodes { id } } }""").ok()["products"]
        assertThat(public["totalCount"].asInt()).isLessThan(all)
        assertThat(public["nodes"].toList().map { it["id"].asString() }).doesNotContain(hidden.id.toString())

        // Product.categories (join)
        val categories = graphql("""{ product(id: "${visible.id}") { categories(orderBy: {field: NAME}) { totalCount hasNextPage nodes { id } } } }""")
            .ok()["product"]["categories"]
        assertThat(categories["totalCount"].asInt()).isEqualTo(1)
        assertThat(categories["nodes"][0]["id"].asString()).isEqualTo(category.id.toString())
    }

    @Test
    fun `categorical characteristic values are distinct, ordered and paginated`() {
        val taxRate = registerTaxRate()
        val category = createCategory()
        val p1 = createProduct(category, taxRate, value = "red")
        createProduct(category, taxRate, value = "blue")
        createProduct(category, taxRate, value = "red")
        graphql(
            """mutation { createProductVariantVersion(input: { productVariantId: "${p1.variant}", name: "x", description: "x", retailPrice: 1,
                 weight: 1, taxRateId: "$taxRate", mediaIds: [], numericalCharacteristicValues: [],
                 categoricalCharacteristicValues: [{ characteristicId: "${category.categorical}", value: "green" }] }) { id } }""",
            user = employee
        ).ok()
        val values = entities(
            "CategoricalCategoryCharacteristic", listOf(category.categorical),
            """ id name category { id }
                all: values { totalCount hasNextPage nodes { value characteristic { id } } }
                desc: values(orderBy: {direction: DESC}, first: 2) { totalCount hasNextPage nodes { value } }
                rest: values(orderBy: {field: VALUE}, first: 2, skip: 2) { hasNextPage nodes { value } } """
        ).ok()["_entities"][0]
        assertThat(values["category"]["id"].asString()).isEqualTo(category.id.toString())
        assertThat(values["all"]["totalCount"].asInt()).isEqualTo(3)
        assertThat(values["all"]["hasNextPage"].asBoolean()).isFalse()
        assertThat(values["all"]["nodes"].toList().map { it["value"].asString() }).containsExactly("blue", "green", "red")
        assertThat(values["all"]["nodes"][0]["characteristic"]["id"].asString()).isEqualTo(category.categorical.toString())
        assertThat(values["desc"]["nodes"].toList().map { it["value"].asString() }).containsExactly("red", "green")
        assertThat(values["desc"]["hasNextPage"].asBoolean()).isTrue()
        assertThat(values["rest"]["nodes"].toList().map { it["value"].asString() }).containsExactly("red")
        assertThat(values["rest"]["hasNextPage"].asBoolean()).isFalse()
    }

    // ---------------------------------------------------------------- federation

    @Test
    fun `entities of every resolvable type`() {
        val taxRate = registerTaxRate()
        val media = registerMedia()
        val category = createCategory()
        val product = createProductRaw(
            listOf(category.id), versionInput(taxRate, mediaIds = listOf(media)), isPublic = false
        ).ok()["createProduct"]
        val productId = product["id"].asString()
        val variantId = product["defaultVariant"]["id"].asString()
        val versionId = product["defaultVariant"]["currentVersion"]["id"].asString()

        val result = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { __typename
                 ... on Product { id isPubliclyVisible } ... on ProductVariant { id } ... on ProductVariantVersion { id name taxRate { id } medias { nodes { id } } }
                 ... on Category { id name } ... on CategoricalCategoryCharacteristic { id } ... on NumericalCategoryCharacteristic { id unit } } }""",
            mapOf(
                "r" to listOf(
                    mapOf("__typename" to "Product", "id" to productId),
                    mapOf("__typename" to "ProductVariant", "id" to variantId),
                    mapOf("__typename" to "ProductVariantVersion", "id" to versionId),
                    mapOf("__typename" to "Category", "id" to category.id.toString()),
                    mapOf("__typename" to "CategoricalCategoryCharacteristic", "id" to category.categorical.toString()),
                    mapOf("__typename" to "NumericalCategoryCharacteristic", "id" to category.numerical.toString()),
                )
            )
        ).ok()["_entities"]
        assertThat(result.toList().map { it["__typename"].asString() }).containsExactly(
            "Product", "ProductVariant", "ProductVariantVersion", "Category",
            "CategoricalCategoryCharacteristic", "NumericalCategoryCharacteristic"
        )
        assertThat(result[0]["id"].asString()).isEqualTo(productId) // hidden product: no check in the entity resolver
        assertThat(result[2]["taxRate"]["id"].asString()).isEqualTo(taxRate.toString())
        assertThat(result[2]["medias"]["nodes"][0]["id"].asString()).isEqualTo(media.toString())
        assertThat(result[5]["unit"].asString()).isEqualTo("GB")

        // FIXED: an unknown id fails only its own entry; a categorical characteristic is no numerical one
        val withUnknown = entities("Product", listOf(productId, UUID.randomUUID()), "id")
        assertThat(withUnknown["errors"]).hasSize(1)
        assertThat(withUnknown["data"]["_entities"][0]["id"].asString()).isEqualTo(productId)
        assertThat(withUnknown["data"]["_entities"][1].isNull).isTrue()
        val wrongType = entities("NumericalCategoryCharacteristic", listOf(category.numerical, category.categorical), "id unit")
        assertThat(wrongType["errors"]).hasSize(1)
        assertThat(wrongType["data"]["_entities"][0]["unit"].asString()).isEqualTo("GB")
        assertThat(wrongType["data"]["_entities"][1].isNull).isTrue()
        val unknownProduct = graphql("""{ product(id: "${UUID.randomUUID()}") { id } }""", user = employee)
        assertThat(unknownProduct.errorMessage()).isEqualTo("Exception while fetching data (/product) : null")
    }
}
