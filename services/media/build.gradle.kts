plugins {
    id("misarch.service-conventions")
}

dependencies {
    // blobs stay in an S3-compatible store like in the original (MinIO there, RustFS in deploy/compose; no database).
    // Vendor-neutral AWS SDK v2 S3 client, path-style, with the SDK's Apache HttpClient 5 transport (the Netty client is
    // built against Netty 4.1 while Boot ships 4.2; the URLConnection client mishandles stale keep-alive connections).
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.aws.sdk.apache5.client)
}
