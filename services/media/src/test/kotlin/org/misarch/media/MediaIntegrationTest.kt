package org.misarch.media

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.misarch.common.event.EventPublisher
import org.misarch.common.testing.FakeDaprSidecar
import org.misarch.common.testing.MisarchIntegrationTest.Companion.authorizedUserHeader
import org.misarch.common.testing.MisarchIntegrationTest.Companion.errorMessage
import org.misarch.media.service.MediaService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.apache5.Apache5HttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Behaviour of the media service against an S3-compatible store in a container (fake Dapr sidecar, no database):
 * GraphQL multipart upload, event, listing, pre-signed paths, authorization. Run against RustFS (the store of
 * deploy/compose) and MinIO, see the subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
abstract class MediaIntegrationTest {

    /** Endpoint of the store container (`MINIO_ENDPOINT`). */
    abstract val s3Endpoint: String

    private val s3: S3Client by lazy { s3Client(s3Endpoint) }

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var configuration: MediaConfiguration

    @Autowired
    lateinit var eventPublisher: EventPublisher

    private val dapr get() = sharedDapr
    private val admin = authorizedUserHeader(UUID.randomUUID(), "admin")
    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")

    @BeforeEach
    fun reset() {
        dapr.reset()
        keys().forEach { key -> s3.deleteObject { it.bucket(MediaConfiguration.BUCKET).key(key) } }
    }

    private fun graphql(query: String, user: String? = null): JsonNode {
        val body = webTestClient.post().uri("/").header("Content-Type", "application/json")
            .apply { if (user != null) header("Authorized-User", user) }
            .bodyValue(mapOf("query" to query)).exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        return mapper.readTree(body)
    }

    /** A GraphQL multipart request (spec: operations, map, file part `0`) for `uploadMedia`. */
    private fun upload(bytes: ByteArray, contentType: String?, user: String? = admin, filename: String? = "a.bin"): JsonNode =
        mapper.readTree(uploadRaw(bytes, contentType, user, filename, 200))

    /** Sends the multipart request and returns the body; asserts the HTTP status. */
    private fun uploadRaw(bytes: ByteArray, contentType: String?, user: String?, filename: String?, status: Int): String {
        val boundary = "----misarch${UUID.randomUUID()}"
        val out = ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray())
        text("--$boundary\r\nContent-Disposition: form-data; name=\"operations\"\r\n\r\n")
        text("""{"query":"mutation (${'$'}file: Upload!) { uploadMedia(mediaFile: ${'$'}file) }","variables":{"file":null}}""")
        text("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"map\"\r\n\r\n{\"0\":[\"variables.file\"]}\r\n")
        text("--$boundary\r\nContent-Disposition: form-data; name=\"0\"" + (filename?.let { "; filename=\"$it\"" } ?: "") + "\r\n")
        if (contentType != null) text("Content-Type: $contentType\r\n")
        text("\r\n")
        out.write(bytes)
        text("\r\n--$boundary--\r\n")
        val body = webTestClient.post().uri("/").header("Content-Type", "multipart/form-data; boundary=$boundary")
            .apply { if (user != null) header("Authorized-User", user) }
            .bodyValue(out.toByteArray()).exchange().expectStatus().isEqualTo(status)
            .expectBody(String::class.java).returnResult().responseBody ?: ""
        return body
    }


    private fun keys(): List<String> =
        s3.listObjectsV2Paginator { it.bucket(MediaConfiguration.BUCKET) }.contents().map { it.key() }

    private fun put(key: String, bytes: ByteArray = byteArrayOf(1)) {
        s3.putObject({ it.bucket(MediaConfiguration.BUCKET).key(key) }, RequestBody.fromBytes(bytes))
    }

    /** A service with other [MediaConfigurationProperties] (same store and sidecar). */
    private fun mediaService(pathExpirationTime: Long = 86400, proxyPath: String = "/api/media"): MediaService {
        val properties = MediaConfigurationProperties(
            URI.create(s3Endpoint), pathExpirationTime = Duration.ofSeconds(pathExpirationTime), proxyPath = proxyPath
        )
        return MediaService(configuration.s3Client(properties), configuration.s3Presigner(properties), properties, eventPublisher)
    }

    private fun uuid(i: Int): UUID = UUID.fromString("%08x-0000-4000-8000-000000000000".format(i))

    @Test
    fun `uploadMedia (GraphQL multipart request) stores the object as octet-stream and publishes media created`() {
        val bytes = "png-bytes".toByteArray()
        val result = upload(bytes, "image/PNG")
        val id = result.path("data").path("uploadMedia").asString()
        assertThat(keys()).isEqualTo(listOf("$id.png"))
        val head = s3.headObject { it.bucket(MediaConfiguration.BUCKET).key("$id.png") }
        assertThat(head.contentType()).isEqualTo("application/octet-stream")
        assertThat(s3.getObjectAsBytes { it.bucket(MediaConfiguration.BUCKET).key("$id.png") }.asByteArray()).isEqualTo(bytes)
        val event = dapr.awaitPublished("media/media/created").single()
        assertThat(event.pubsubName).isEqualTo("pubsub")
        assertThat(event.body).isEqualTo("""{"id":"$id"}""")
        assertThat(event.contentType!!).startsWith("application/json")
        // svg+xml -> svg; parameters are not part of the extension
        val svg = upload("<svg/>".toByteArray(), "image/svg+xml; charset=utf-8").path("data").path("uploadMedia").asString()
        assertThat(keys()).contains("$svg.svg")
    }

    @Test
    fun `uploadMedia authorization and content type errors`() {
        val buyerId = UUID.randomUUID()
        assertThat(errorMessage(upload(byteArrayOf(1), "image/png", authorizedUserHeader(buyerId, "buyer")))).endsWith("$buyerId is not an employee or admin")
        assertThat(errorMessage(upload(byteArrayOf(1), "image/png", null))).endsWith("Unauthorized access")
        assertThat(errorMessage(upload(byteArrayOf(1), null))).endsWith("Content type of file upload does not exist.")
        // a part content type that Spring cannot parse fails the whole request
        uploadRaw(byteArrayOf(1), "not a type", admin, "a.bin", 400)
        // a part without filename is not a file: the variable stays null
        assertThat(upload(byteArrayOf(1), "image/png", filename = null).path("errors").size() > 0).isTrue()
        assertThat(keys()).isEmpty()
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `uploadMedia fails and keeps no object when the publish fails`() {
        dapr.publishStatus = 500
        val failed = upload(byteArrayOf(1), "image/jpeg")
        assertThat(errorMessage(failed)).contains("500")
        assertThat(failed.path("data").path("uploadMedia").isString).isFalse()
        assertThat(keys()).isEmpty()
        dapr.publishStatus = 204
        val id = upload(byteArrayOf(3), "image/jpeg").path("data").path("uploadMedia").asString()
        assertThat(keys()).isEqualTo(listOf("$id.jpeg"))
    }

    @Test
    fun `PROXY_PATH and PATH_EXPIRATION_TIME`() {
        put("${uuid(7)}.png")
        val path = runBlocking { mediaService(pathExpirationTime = 60, proxyPath = "/files").presignedPath(uuid(7)) }
        assertThat(path).startsWith("/files/media-data/${uuid(7)}.png?")
        assertThat(path).contains("X-Amz-Expires=60")
        // pre-signed URLs are valid for at most one week
        assertThatThrownBy { runBlocking { mediaService(pathExpirationTime = 604801).presignedPath(uuid(7)) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `getMedias lists the bucket in key order with skip and first`() {
        assertThat(mapper.writeValueAsString(graphql("{ getMedias { nodes { id } hasNextPage totalCount } }", admin).path("data").path("getMedias"))).isEqualTo("""{"nodes":[],"hasNextPage":false,"totalCount":0}""")
        (1..4).forEach { put("${uuid(it)}.png") }
        val all = graphql("{ getMedias { nodes { id } hasNextPage totalCount } }", employee).path("data").path("getMedias")
        assertThat(all.path("nodes").toList().map { it.path("id").asString() }).isEqualTo((1..4).map { uuid(it).toString() })
        assertThat(all.path("totalCount").asInt()).isEqualTo(4)
        assertThat(all.path("hasNextPage").asBoolean()).isFalse()
        val window = graphql("{ getMedias(first: 2, skip: 1) { nodes { id } hasNextPage totalCount } }", admin).path("data").path("getMedias")
        assertThat(window.path("nodes").toList().map { it.path("id").asString() }).isEqualTo(listOf(uuid(2), uuid(3)).map(UUID::toString))
        assertThat(window.path("hasNextPage").asBoolean()).isTrue()
        val empty = graphql("{ getMedias(first: 0) { nodes { id } hasNextPage } }", admin).path("data").path("getMedias")
        assertThat(empty.path("nodes").size()).isEqualTo(0)
        assertThat(empty.path("hasNextPage").asBoolean()).isTrue()
        val beyond = graphql("{ getMedias(skip: 10) { nodes { id } hasNextPage totalCount } }", admin).path("data").path("getMedias")
        assertThat(beyond.path("nodes").size()).isEqualTo(0)
        assertThat(beyond.path("hasNextPage").asBoolean()).isFalse()
        // a foreign object name only fails when it falls into the window
        put("zzz-not-a-uuid.txt")
        assertThat(graphql("{ getMedias(first: 4) { nodes { id } } }", admin).path("data").path("getMedias").path("nodes").size()).isEqualTo(4)
        val failing = graphql("{ getMedias { nodes { id } } }", admin)
        assertThat(errorMessage(failing)).contains("zzz-not-a-uuid")
        assertThat(errorMessage(graphql("{ getMedias { totalCount } }"))).endsWith("Unauthorized access")
        assertThat(errorMessage(graphql("{ getMedias(first: -1) { totalCount } }", admin))).endsWith("Limit must not be negative.")
        assertThat(errorMessage(graphql("{ getMedias(skip: -1) { totalCount } }", admin))).endsWith("Offset must not be negative.")
    }

    @Test
    fun `getMedias sees all listing pages`() {
        (1..1001).forEach { put("${uuid(it)}.bin") }
        val all = graphql("{ getMedias { nodes { id } hasNextPage totalCount } }", admin).path("data").path("getMedias")
        assertThat(all.path("totalCount").asInt()).isEqualTo(1001)
        assertThat(all.path("nodes").toList().map { it.path("id").asString() }).isEqualTo((1..1001).map { uuid(it).toString() })
        assertThat(all.path("hasNextPage").asBoolean()).isFalse()
        val page = graphql("{ getMedias(first: 2, skip: 999) { nodes { id } hasNextPage totalCount } }", admin).path("data").path("getMedias")
        assertThat(page.path("totalCount").asInt()).isEqualTo(1001)
        assertThat(page.path("nodes").toList().map { it.path("id").asString() }).isEqualTo(listOf(uuid(1000), uuid(1001)).map(UUID::toString))
        assertThat(page.path("hasNextPage").asBoolean()).isFalse()
    }

    @Test
    fun `path is a pre-signed URL behind PROXY_PATH that downloads the object`() {
        val bytes = "hello media".toByteArray()
        val id = upload(bytes, "text/plain").path("data").path("uploadMedia").asString()
        val path = graphql("""{ _entities(representations: [{__typename: "Media", id: "$id"}]) { ... on Media { id path } } }""")
            .path("data").path("_entities").path(0).path("path").asString()
        assertThat(path).startsWith("/api/media/media-data/$id.plain?")
        assertThat(path.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256") && path.contains("X-Amz-Signature=")).describedAs(path).isTrue()
        assertThat(path).contains("X-Amz-Expires=86400")
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(s3Endpoint + path.removePrefix("/api/media"))).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo(bytes)

        val unknown = UUID.randomUUID()
        val missing = graphql("""{ _entities(representations: [{__typename: "Media", id: "$unknown"}]) { ... on Media { id path } } }""")
        assertThat(errorMessage(missing)).endsWith(" : null")
        val idOnly = graphql("""{ _entities(representations: [{__typename: "Media", id: "$unknown"}]) { ... on Media { id } } }""")
        assertThat(idOnly.path("data").path("_entities").path(0).path("id").asString()).isEqualTo(unknown.toString())
        assertThat(graphql("""{ _entities(representations: [{__typename: "Media", id: "x"}]) { __typename } }""").get("errors")).isNotNull()
        // getMedias nodes resolve their path too
        val nodes = graphql("{ getMedias { nodes { path } } }", admin).path("data").path("getMedias").path("nodes")
        assertThat(nodes.path(0).path("path").asString()).startsWith("/api/media/media-data/$id.plain?")
    }

    @Test
    fun `no dapr subscriptions, health`() {
        webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk.expectBody().json("[]")
        webTestClient.get().uri("/health").exchange().expectStatus().isOk.expectBody().json("""{"status":"UP"}""")
    }

    companion object {
        /** Credentials of the store containers (the defaults of [MediaConfigurationProperties]). */
        const val ACCESS_KEY = "admin"
        const val SECRET_KEY = "password"

        private val mapper = JsonMapper.builder().build()
        val sharedDapr: FakeDaprSidecar by lazy { FakeDaprSidecar() }

        fun s3Client(endpoint: String): S3Client = S3Client.builder()
            .endpointOverride(URI.create(endpoint)).forcePathStyle(true).region(Region.of(MediaConfiguration.REGION))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .httpClient(Apache5HttpClient.create())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .build()

        fun register(registry: DynamicPropertyRegistry, endpoint: () -> String) {
            registry.add("MINIO_ENDPOINT", endpoint)
            registry.add("misarch.dapr.http-endpoint") { sharedDapr.endpoint }
        }
    }
}

/** Against RustFS 1.0.1 (the S3 store of deploy/compose). */
class RustfsMediaIntegrationTest : MediaIntegrationTest() {
    override val s3Endpoint: String get() = endpoint

    companion object {
        const val IMAGE = "rustfs/rustfs:1.0.1"

        private val container: GenericContainer<*> by lazy {
            GenericContainer(IMAGE)
                .withEnv("RUSTFS_ACCESS_KEY", MediaIntegrationTest.ACCESS_KEY)
                .withEnv("RUSTFS_SECRET_KEY", MediaIntegrationTest.SECRET_KEY)
                .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/health/ready").forPort(9000))
                .also { it.start() }
        }

        val endpoint: String get() = "http://${container.host}:${container.getMappedPort(9000)}"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = register(registry) { endpoint }
    }
}

/**
 * Against MinIO (`minio/minio` is no longer pullable: Docker Hub repository
 * removed, quay.io 401; this is the same MinIO server, pinned).
 */
class MinioMediaIntegrationTest : MediaIntegrationTest() {
    override val s3Endpoint: String get() = endpoint

    companion object {
        const val IMAGE = "bitnamilegacy/minio:2025.7.23"

        private val container: GenericContainer<*> by lazy {
            GenericContainer(IMAGE)
                .withEnv("MINIO_ROOT_USER", MediaIntegrationTest.ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", MediaIntegrationTest.SECRET_KEY)
                .withEnv("MINIO_REGION", MediaConfiguration.REGION)
                .withExposedPorts(9000)
                // the bitnami entrypoint starts MinIO, stops it after its setup and starts it again: wait for the final start
                .waitingFor(
                    WaitAllStrategy()
                        .withStrategy(Wait.forLogMessage(".*API: http.*\\n", 1))
                        .withStrategy(Wait.forHttp("/minio/health/ready").forPort(9000))
                )
                .also { it.start() }
        }

        val endpoint: String get() = "http://${container.host}:${container.getMappedPort(9000)}"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = register(registry) { endpoint }
    }
}
