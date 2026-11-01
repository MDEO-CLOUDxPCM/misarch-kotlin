package org.misarch.media

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MediaApplication

fun main(args: Array<String>) {
    runApplication<MediaApplication>(*args)
}
