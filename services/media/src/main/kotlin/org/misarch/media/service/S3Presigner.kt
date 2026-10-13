package org.misarch.media.service

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `Bucket::presign_get` of rust-s3 0.33 (path style, AWS SigV4 query signing, `UNSIGNED-PAYLOAD`), producing the same
 * URL shape and parameter order:
 * `<endpoint>/<bucket>/<key>?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=<ak>%2F<date>%2F<region>%2Fs3%2Faws4_request&X-Amz-Date=<ts>&X-Amz-Expires=<n>&X-Amz-SignedHeaders=host&X-Amz-Signature=<hex>`.
 */
class S3Presigner(
    endpoint: String,
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
) {
    private val base: URI = URI.create(endpoint.trimEnd('/'))
    private val host: String = if (base.port == -1) base.host else "${base.host}:${base.port}"

    /** @throws IllegalArgumentException rust-s3's `MaxExpiry` error for more than one week */
    fun presignGet(bucket: String, key: String, expirySecs: Long, now: Instant = Instant.now()): String {
        if (expirySecs > MAX_EXPIRY) {
            throw IllegalArgumentException(
                "Max expiration for presigned URLs is one week, or 604.800 seconds, got $expirySecs instead"
            )
        }
        val longDate = LONG_DATE.format(now)
        val shortDate = longDate.substring(0, 8)
        val scope = "$shortDate/$region/s3/aws4_request"
        val path = "/" + uriEncode(bucket, false) + "/" + uriEncode(key, false)
        val query = "X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=" + uriEncode("$accessKey/$scope", true) +
            "&X-Amz-Date=$longDate" +
            "&X-Amz-Expires=$expirySecs" +
            "&X-Amz-SignedHeaders=host"
        val canonicalRequest = "GET\n$path\n$query\nhost:$host\n\nhost\nUNSIGNED-PAYLOAD"
        val stringToSign = "AWS4-HMAC-SHA256\n$longDate\n$scope\n" + hex(sha256(canonicalRequest))
        var key2 = hmac(("AWS4$secretKey").toByteArray(), shortDate)
        key2 = hmac(key2, region)
        key2 = hmac(key2, "s3")
        key2 = hmac(key2, "aws4_request")
        return "${base.scheme}://$host$path?$query&X-Amz-Signature=${hex(hmac(key2, stringToSign))}"
    }

    companion object {
        const val MAX_EXPIRY: Long = 604800
        private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** SigV4 URI encoding (unreserved characters kept, `/` kept unless [encodeSlash]). */
        fun uriEncode(s: String, encodeSlash: Boolean): String = buildString {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' ||
                    (c == '/' && !encodeSlash)
                ) append(c) else append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
