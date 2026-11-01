plugins {
    id("misarch.service-conventions")
}

dependencies {
    implementation(libs.spring.boot.starter.amqp)
}
