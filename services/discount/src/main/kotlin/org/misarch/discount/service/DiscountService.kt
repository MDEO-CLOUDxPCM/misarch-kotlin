package org.misarch.discount.service

import kotlinx.coroutines.flow.toList
import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.ifPresent
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.ConditionQueries
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.common.persistence.requiredId
import org.misarch.discount.event.DiscountEvents
import org.misarch.discount.event.model.OrderDTO
import org.misarch.discount.event.model.ValidationFailedDTO
import org.misarch.discount.graphql.input.CreateDiscountInput
import org.misarch.discount.graphql.input.FindApplicableDiscountsInput
import org.misarch.discount.graphql.input.FindApplicableDiscountsProductVariantInput
import org.misarch.discount.graphql.input.UpdateDiscountInput
import org.misarch.discount.graphql.model.DiscountsForProductVariant
import org.misarch.discount.persistence.model.CategoryEntity
import org.misarch.discount.persistence.model.CouponEntity
import org.misarch.discount.persistence.model.CouponRedemptionEntity
import org.misarch.discount.persistence.model.DiscountEntity
import org.misarch.discount.persistence.model.DiscountToCategoryEntity
import org.misarch.discount.persistence.model.DiscountToCategoryKey
import org.misarch.discount.persistence.model.DiscountToProductEntity
import org.misarch.discount.persistence.model.DiscountToProductKey
import org.misarch.discount.persistence.model.DiscountToProductVariantEntity
import org.misarch.discount.persistence.model.DiscountToProductVariantKey
import org.misarch.discount.persistence.model.ProductEntity
import org.misarch.discount.persistence.model.ProductToCategoryEntity
import org.misarch.discount.persistence.model.ProductVariantEntity
import org.misarch.discount.persistence.repository.CouponRepository
import org.misarch.discount.persistence.repository.DiscountRepository
import org.misarch.discount.persistence.repository.DiscountToCategoryRepository
import org.misarch.discount.persistence.repository.DiscountToProductRepository
import org.misarch.discount.persistence.repository.DiscountToProductVariantRepository
import org.misarch.discount.persistence.repository.ProductVariantRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Service for [DiscountEntity]s
 */
@Service
class DiscountService(
    private val repository: DiscountRepository,
    private val productVariantRepository: ProductVariantRepository,
    private val discountToCategoryRepository: DiscountToCategoryRepository,
    private val discountToProductRepository: DiscountToProductRepository,
    private val discountToProductVariantRepository: DiscountToProductVariantRepository,
    private val couponRepository: CouponRepository,
    private val discountUsageService: DiscountUsageService,
    private val eventPublisher: EventPublisher,
    private val conditionQueries: ConditionQueries,
    private val clock: Clock
) {

    private val logger = LoggerFactory.getLogger(DiscountService::class.java)

    /**
     * Creates a discount
     *
     * @param discountInput the discount to create
     * @return the created discount
     */
    @Transactional
    suspend fun createDiscount(discountInput: CreateDiscountInput): DiscountEntity {
        ensureReferencedEntitiesExist(discountInput)
        require(discountInput.discount >= 0) { "A discount cannot increase the price and thus must be >= 0" }
        require(discountInput.discount <= 1) { "A discount cannot be larger than 1" }
        val discount = DiscountEntity(
            discount = discountInput.discount,
            maxUsagesPerUser = discountInput.maxUsagesPerUser,
            validUntil = discountInput.validUntil,
            validFrom = discountInput.validFrom,
            minOrderAmount = discountInput.minOrderAmount,
            id = null
        )
        val savedDiscount = repository.save(discount)
        val categoryIds = discountInput.discountAppliesToCategoryIds.toSet()
        val productIds = discountInput.discountAppliesToProductIds.toSet()
        val productVariantIds = discountInput.discountAppliesToProductVariantIds.toSet()
        addAppliedToReferences(savedDiscount.requiredId, categoryIds, productIds, productVariantIds)
        eventPublisher.publishEvent(
            DiscountEvents.DISCOUNT_CREATED, savedDiscount.toEventDTO(categoryIds, productIds, productVariantIds)
        )
        return savedDiscount
    }

    /**
     * Updates a discount
     *
     * @param discountInput the input for the update
     * @return the updated discount
     */
    @Transactional
    suspend fun updateDiscount(discountInput: UpdateDiscountInput): DiscountEntity {
        val discount = repository.findByIdOrThrow(discountInput.id)
        discountInput.maxUsagesPerUser.ifPresent { discount.maxUsagesPerUser = it }
        if (discountInput.validUntil != null) {
            discount.validUntil = discountInput.validUntil
        }
        if (discountInput.validFrom != null) {
            discount.validFrom = discountInput.validFrom
        }
        discountInput.minOrderAmount.ifPresent { discount.minOrderAmount = it }
        updateDiscountReferencedEntities(discountInput)
        val updatedDiscount = repository.save(discount)
        eventPublisher.publishEvent(
            DiscountEvents.DISCOUNT_UPDATED, updatedDiscount.toEventDTO(
                discountToCategoryRepository.findByKeyDiscountId(discountInput.id).map { it.key.categoryId }.toSet(),
                discountToProductRepository.findByKeyDiscountId(discountInput.id).map { it.key.productId }.toSet(),
                discountToProductVariantRepository.findByKeyDiscountId(discountInput.id).map { it.key.productVariantId }.toSet()
            )
        )
        return updatedDiscount
    }

    /**
     * Updates the entities the discount applies to, and removes the entities the discount no longer applies to
     *
     * @param discountInput the input for the update
     * @throws IllegalArgumentException if any of the referenced entities do not exist
     */
    private suspend fun updateDiscountReferencedEntities(discountInput: UpdateDiscountInput) {
        val categoryIds = discountInput.addedDiscountAppliesToCategoryIds?.toSet() ?: emptySet()
        val productIds = discountInput.addedDiscountAppliesToProductIds?.toSet() ?: emptySet()
        val productVariantIds = discountInput.addedDiscountAppliesToProductVariantIds?.toSet() ?: emptySet()
        ensureEntitiesExist(categoryIds, productIds, productVariantIds)
        addAppliedToReferences(discountInput.id, categoryIds, productIds, productVariantIds)
        discountInput.removedDiscountAppliesToCategoryIds?.forEach {
            discountToCategoryRepository.deleteById(DiscountToCategoryKey(discountInput.id, it))
        }
        discountInput.removedDiscountAppliesToProductIds?.forEach {
            discountToProductRepository.deleteById(DiscountToProductKey(discountInput.id, it))
        }
        discountInput.removedDiscountAppliesToProductVariantIds?.forEach {
            discountToProductVariantRepository.deleteById(DiscountToProductVariantKey(discountInput.id, it))
        }
    }

    /**
     * Checks that the entities referenced by [discountInput] exist
     *
     * @param discountInput the discount input
     * @throws IllegalArgumentException if any of the referenced entities do not exist
     */
    private suspend fun ensureReferencedEntitiesExist(discountInput: CreateDiscountInput) {
        val categoryIds = discountInput.discountAppliesToCategoryIds.toSet()
        val productIds = discountInput.discountAppliesToProductIds.toSet()
        val productVariantIds = discountInput.discountAppliesToProductVariantIds.toSet()
        require(categoryIds.isNotEmpty() || productIds.isNotEmpty() || productVariantIds.isNotEmpty()) {
            "At least one category, product or product variant must be specified"
        }
        ensureEntitiesExist(categoryIds, productIds, productVariantIds)
    }

    /**
     * Checks that the entities referenced by [categoryIds], [productIds] and [productVariantIds] exist
     *
     * @param categoryIds the category ids
     * @param productIds the product ids
     * @param productVariantIds the product variant ids
     * @throws IllegalArgumentException if any of the referenced entities do not exist
     */
    private suspend fun ensureEntitiesExist(
        categoryIds: Set<UUID>, productIds: Set<UUID>, productVariantIds: Set<UUID>
    ) {
        val missingCategories = categoryIds - conditionQueries.existingIds<CategoryEntity>(categoryIds)
        require(missingCategories.isEmpty()) { "Categories with ids ${missingCategories.toList()} do not exist" }
        val missingProducts = productIds - conditionQueries.existingIds<ProductEntity>(productIds)
        require(missingProducts.isEmpty()) { "Products with ids ${missingProducts.toList()} do not exist" }
        val missingProductVariants = productVariantIds - conditionQueries.existingIds<ProductVariantEntity>(productVariantIds)
        require(missingProductVariants.isEmpty()) { "Product variants with ids ${missingProductVariants.toList()} do not exist" }
    }

    /**
     * Adds the applied to references to the discount
     *
     * @param id the id of the discount
     * @param discountAppliesToCategoryIds the category ids to which the discount applies
     * @param discountAppliesToProductIds the product ids to which the discount applies
     * @param discountAppliesToProductVariantIds the product variant ids to which the discount applies
     */
    private suspend fun addAppliedToReferences(
        id: UUID,
        discountAppliesToCategoryIds: Set<UUID>,
        discountAppliesToProductIds: Set<UUID>,
        discountAppliesToProductVariantIds: Set<UUID>
    ) {
        discountToCategoryRepository.saveAll(discountAppliesToCategoryIds.map {
            DiscountToCategoryEntity(DiscountToCategoryKey(id, it))
        }).toList()
        discountToProductRepository.saveAll(discountAppliesToProductIds.map {
            DiscountToProductEntity(DiscountToProductKey(id, it))
        }).toList()
        discountToProductVariantRepository.saveAll(discountAppliesToProductVariantIds.map {
            DiscountToProductVariantEntity(DiscountToProductVariantKey(id, it))
        }).toList()
    }

    /**
     * Validates an order: checks that the user can still use the discounts with the amounts of the items, and records
     * the usages (does NOT validate whether the discounts are usable with the items of the order).
     *
     * The usage upserts and the result event run in one transaction ([DiscountUsageService.validateOrder]). An
     * exception (e.g. the usage trigger rejecting a concurrent order of the same user) rolls the transaction back and
     * publishes `validation-failed` with an empty list of failing discounts once; the event is then acknowledged, so it
     * is not redelivered. Only when that publish itself fails (Dapr unavailable) the exception propagates, so that
     * Dapr redelivers.
     *
     * @param order the order to validate
     */
    suspend fun validateOrder(order: OrderDTO) {
        try {
            discountUsageService.validateOrder(order)
        } catch (e: Exception) {
            logger.warn("Validation of order {} failed: {}", order.id, e.toString())
            eventPublisher.publishEvent(DiscountEvents.VALIDATION_FAILED, ValidationFailedDTO(order, emptyList()))
        }
    }

    /**
     * Finds all discounts that are applicable to a user, and a list of product variant and a list of coupons.
     * Also checks if the user can use the discounts with the amount of items.
     *
     * @param input the input for the query
     * @return The applicable discounts for each product variant in order
     * @throws IllegalArgumentException if any of the coupons are not applicable, or multiple coupons are used for the same discount
     */
    suspend fun findApplicableDiscounts(
        input: FindApplicableDiscountsInput
    ): List<DiscountsForProductVariant> {
        verifyProductVariants(input)
        val productVariantInputsWithDiscounts = input.productVariants.map { productVariantInput ->
            val discounts = findApplicableDiscountsForProductVariant(productVariantInput, input)
            Pair(productVariantInput, discounts)
        }
        val discounts = productVariantInputsWithDiscounts.flatMap { it.second }.toSet()
        val remainingUsages = discountUsageService.findRemainingUsages(input.userId, discounts).toMutableMap()
        val couponIds = input.productVariants.flatMap { it.couponIds }.toSet()
        val couponsById = couponRepository.findAllById(couponIds).toList().associateBy { it.id }
        require(couponIds.all { it in couponsById }) {
            "Coupon(s) with id(s) ${couponIds.filter { it !in couponsById }} do(es) not exist"
        }
        return productVariantInputsWithDiscounts.map { (productVariantInput, discounts) ->
            filterApplicableDiscounts(discounts, remainingUsages, productVariantInput, couponsById)
        }
    }

    /**
     * Finds all discounts that are applicable to a user, and a product variant and a list of coupons.
     * Does NOT check if the user can use the discounts with the amount of items.
     *
     * The user's discount usage is not joined: it cannot change the result (unique constraint on (discountId, userId),
     * only discount columns selected). No ORDER BY.
     *
     * @param productVariantInput defines the product variant, the count, and the coupons to check
     * @param input defines the id of the user for which to check the coupons and the order amount
     * @return The applicable discounts for the product variant, count and user
     */
    private suspend fun findApplicableDiscountsForProductVariant(
        productVariantInput: FindApplicableDiscountsProductVariantInput,
        input: FindApplicableDiscountsInput
    ): List<DiscountEntity> {
        val condition = generateApplicableDiscountsFilterCondition(productVariantInput, input)
        return conditionQueries.findAll<DiscountEntity>(condition)
    }

    /**
     * Verifies that the product variants in [input] exist
     *
     * @param input the input for the query
     * @throws IllegalArgumentException if any of the product variants do not exist
     */
    private suspend fun verifyProductVariants(input: FindApplicableDiscountsInput) {
        val productVariantIds = input.productVariants.map { it.productVariantId }.toSet()
        val productVariantsById =
            productVariantRepository.findAllById(productVariantIds).toList().associateBy { it.id }
        require(productVariantIds.all { it in productVariantsById }) {
            "Product variant(s) with id(s) ${productVariantIds.filter { it !in productVariantsById }} do(es) not exist"
        }
    }

    /**
     * Returns the [discounts] which can be used with [productVariantInput].
     * Ensures that all coupons are applicable and refer to different discounts.
     * Ensures that the user can use the discount with the amount of items, and updates [remainingUsages] accordingly.
     *
     * @param discounts the discounts to filter
     * @param remainingUsages the remaining usages for each discount
     * @param productVariantInput the input for the query
     * @param couponsById the coupons by id
     * @return The applicable discounts with the associated product variant and count
     */
    internal fun filterApplicableDiscounts(
        discounts: List<DiscountEntity>,
        remainingUsages: MutableMap<UUID, Long>,
        productVariantInput: FindApplicableDiscountsProductVariantInput,
        couponsById: Map<UUID?, CouponEntity>
    ): DiscountsForProductVariant {
        val usableDiscounts = discounts.filter { discount ->
            remainingUsages[discount.id]?.let { it >= productVariantInput.count } ?: true
        }
        usableDiscounts.forEach { discount ->
            // discounts without a per-user limit are absent from the map (unlimited)
            remainingUsages[discount.requiredId]?.let { remainingUsages[discount.requiredId] = it - productVariantInput.count }
        }
        val coupons = productVariantInput.couponIds.map { couponsById.getValue(it) }
        val discountsById = usableDiscounts.associateBy { it.id }
        coupons.forEach {
            require(it.discountId in discountsById) {
                "Coupon with id ${it.id} could not be used, either due to insufficient remaining usages, the user not owning the coupon, or the coupon not being applicable"
            }
        }
        coupons.groupBy({ it.discountId }) { it.id }.filter { it.value.size > 1 }.forEach { (discountId, couponsIds) ->
            error("Coupons with ids $couponsIds are used multiple times for the same discount $discountId")
        }
        return DiscountsForProductVariant(
            productVariantInput.productVariantId, productVariantInput.count, usableDiscounts.map { it.toDTO() }
        )
    }

    /**
     * Generates a condition that checks that a discount is applicable to a product variant, a user and a list of coupons.
     *
     * @param productVariantInput defines the product variant, count and coupons to check
     * @param input defines the user id and the order amount to check
     * @return The condition
     */
    private fun generateApplicableDiscountsFilterCondition(
        productVariantInput: FindApplicableDiscountsProductVariantInput, input: FindApplicableDiscountsInput
    ): Condition {
        val appliesCondition = generateDiscountAppliesCondition(productVariantInput.productVariantId)
        val noCouponsCondition = generateNoCouponsCondition()
        val couponsCondition = generateUserHasCouponCondition(productVariantInput, input.userId)
        val currentlyValidCondition = generateIsCurrentlyValidCondition()
        val minOrderAmountCondition = generateMinOrderAmountCondition(input.orderAmount)
        val condition = appliesCondition.and(noCouponsCondition.or(couponsCondition)).and(currentlyValidCondition)
            .and(minOrderAmountCondition)
        return condition
    }

    /**
     * Generates a condition that checks that a discount either does not require a min order amount,
     * or the order amount is less than or equal to the provided [amount]
     *
     * @param amount the order amount
     * @return The condition
     */
    private fun generateMinOrderAmountCondition(amount: Int): Condition {
        return Condition.isNull("t.minOrderAmount").or(Condition.le("t.minOrderAmount", amount))
    }

    /**
     * Generates a condition that filters for discounts where any of the coupons in [input] are required
     * and the user has the coupon.
     *
     * @param input defines the user and coupons to check
     * @param userId the id of the user for which to check the coupons
     * @return The condition
     */
    private fun generateUserHasCouponCondition(
        input: FindApplicableDiscountsProductVariantInput, userId: UUID
    ): Condition {
        val filter = Condition.inArray("c.id", input.couponIds).and(Condition.eq("cr.userId", userId))
        return Condition(
            "t.id IN (SELECT c.discountId FROM CouponEntity c " +
                "INNER JOIN CouponRedemptionEntity cr ON cr.couponId = c.id WHERE ${filter.sql})",
            filter.values
        )
    }

    /**
     * Generates a condition that checks that a discount either applies to the product variant, the owning product,
     * or any of the categories of the product.
     * Also checks that either no coupons are required, or the user has a coupon for the discount.
     *
     * @param id the id of the product variant
     * @param authorizedUser the authorized user, or null if the user is not authenticated
     * @return The condition
     */
    fun generateFullDiscountAppliesCondition(id: UUID, authorizedUser: AuthorizedUser?): Condition {
        val appliesCondition = generateDiscountAppliesCondition(id)
        val hasNoRequiredCouponsCondition = generateNoCouponsCondition()
        val couponsCondition = if (authorizedUser == null) {
            hasNoRequiredCouponsCondition
        } else {
            val userHasCouponCondition = Condition.raw(
                "EXISTS (SELECT TRUE FROM CouponEntity c INNER JOIN CouponRedemptionEntity cr " +
                    "ON c.id = cr.couponId WHERE c.discountId = t.id AND cr.userId = ?)",
                authorizedUser.id
            )
            hasNoRequiredCouponsCondition.or(userHasCouponCondition)
        }
        val currentlyValidCondition = generateIsCurrentlyValidCondition()
        val discountAppliesCondition = appliesCondition.and(couponsCondition).and(currentlyValidCondition)
        return discountAppliesCondition
    }

    /**
     * Generates a condition that checks that a discount is currently valid
     *
     * @return The condition
     */
    private fun generateIsCurrentlyValidCondition(): Condition {
        val now = OffsetDateTime.now(clock)
        return Condition.le("t.validFrom", now).and(Condition.ge("t.validUntil", now))
    }

    /**
     * Generates a condition that checks that no coupons are required for a discount
     *
     * @return The condition
     */
    private fun generateNoCouponsCondition(): Condition {
        return Condition.raw("NOT EXISTS (SELECT TRUE FROM CouponEntity c WHERE c.discountId = t.id)")
    }

    /**
     * Generates a condition that checks that a discount either applies to the product variant, the owning product,
     * or any of the categories of the product.
     * Does NOT check for required coupons.
     *
     * @param id the id of the product variant
     * @return The condition
     */
    fun generateDiscountAppliesCondition(id: UUID): Condition {
        val appliesProductVariantCondition = generateDiscountAppliesToProductVariantCondition(id)
        val appliesProductCondition = generateDiscountAppliesToProductCondition(id)
        val appliesCategoryCondition = generateDiscountAppliesToCategoryCondition(id)
        return appliesProductVariantCondition.or(appliesProductCondition).or(appliesCategoryCondition)
    }

    /**
     * Generates a condition that checks that a discount applies to a product variant
     *
     * @param id the id of the product variant
     * @return The condition
     */
    private fun generateDiscountAppliesToProductVariantCondition(id: UUID): Condition {
        return Condition.raw(
            "t.id IN (SELECT dpv.discountId FROM DiscountToProductVariantEntity dpv WHERE dpv.productVariantId = ?)",
            id
        )
    }

    /**
     * Generates a condition that checks that a discount applies to a product
     *
     * @param id the id of the product variant
     * @return The condition
     */
    private fun generateDiscountAppliesToProductCondition(id: UUID): Condition {
        return Condition.raw(
            "t.id IN (SELECT dp.discountId FROM DiscountToProductEntity dp " +
                "INNER JOIN ProductVariantEntity pv ON dp.productId = pv.productId WHERE pv.id = ?)",
            id
        )
    }

    /**
     * Generates a condition that checks that a discount applies to the categories of a product of a product variant
     *
     * @param id the id of the product variant
     * @return The condition
     */
    private fun generateDiscountAppliesToCategoryCondition(id: UUID): Condition {
        return Condition.raw(
            "t.id IN (SELECT dc.discountId FROM DiscountToCategoryEntity dc " +
                "INNER JOIN ProductToCategoryEntity pc ON dc.categoryId = pc.categoryId " +
                "INNER JOIN ProductVariantEntity pv ON pc.productId = pv.productId WHERE pv.id = ?)",
            id
        )
    }

}
