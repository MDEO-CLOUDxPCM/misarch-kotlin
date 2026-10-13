plugins {
    id("misarch.service-conventions")
}

// The simulation keeps RabbitMQ (docs/specs/simulation.md §6): plain RabbitMQ Java client, used like amqplib.
dependencies {
    implementation(libs.rabbitmq.amqp.client)
}
