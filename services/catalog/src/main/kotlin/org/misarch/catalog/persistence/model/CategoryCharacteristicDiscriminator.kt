package org.misarch.catalog.persistence.model

/**
 * Discriminator to distinguish between categorical and numerical characteristics
 */
enum class CategoryCharacteristicDiscriminator {
    /**
     * Values have no deeper meaning, i.e. {@code black}
     */
    CATEGORICAL,

    /**
     * Values have an algebraic meaning, i.e. {@code 8GB}
     */
    NUMERICAL
}