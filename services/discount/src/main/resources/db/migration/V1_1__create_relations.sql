CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE UserEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE ProductEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE ProductVariantEntity (
    id UUID PRIMARY KEY,
    productId UUID NOT NULL
);

CREATE TABLE CategoryEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE ProductToCategoryEntity (
    productId UUID NOT NULL,
    categoryId UUID NOT NULL,
    PRIMARY KEY (productId, categoryId)
);

CREATE TABLE DiscountEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    discount DOUBLE PRECISION NOT NULL,
    maxUsagesPerUser INTEGER,
    validFrom TIMESTAMPTZ NOT NULL,
    validUntil TIMESTAMPTZ NOT NULL,
    minOrderAmount INTEGER
);

CREATE TABLE CouponEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    usages INTEGER NOT NULL DEFAULT 0,
    maxUsages INTEGER,
    validFrom TIMESTAMPTZ NOT NULL,
    validUntil TIMESTAMPTZ NOT NULL,
    code TEXT NOT NULL,
    discountId UUID NOT NULL,
    FOREIGN KEY (discountId) REFERENCES DiscountEntity(id),
    UNIQUE (code)
);
CREATE INDEX couponEntity_discountId ON CouponEntity (discountId);

CREATE TABLE CouponRedemptionEntity (
    couponId UUID NOT NULL,
    userId UUID NOT NULL,
    PRIMARY KEY (couponId, userId),
    FOREIGN KEY (couponId) REFERENCES CouponEntity(id),
    FOREIGN KEY (userId) REFERENCES UserEntity(id)
);
CREATE INDEX couponRedemptionEntity_userId ON CouponRedemptionEntity (userId);

CREATE TABLE DiscountToProductEntity (
    discountId UUID NOT NULL,
    productId UUID NOT NULL,
    PRIMARY KEY (discountId, productId),
    FOREIGN KEY (discountId) REFERENCES DiscountEntity(id),
    FOREIGN KEY (productId) REFERENCES ProductEntity(id)
);
CREATE INDEX discountToProductEntity_productId ON DiscountToProductEntity (productId);

CREATE TABLE DiscountToCategoryEntity (
    discountId UUID NOT NULL,
    categoryId UUID NOT NULL,
    PRIMARY KEY (discountId, categoryId),
    FOREIGN KEY (discountId) REFERENCES DiscountEntity(id),
    FOREIGN KEY (categoryId) REFERENCES CategoryEntity(id)
);
CREATE INDEX discountToCategoryEntity_categoryId ON DiscountToCategoryEntity (categoryId);

CREATE TABLE DiscountToProductVariantEntity (
    discountId UUID NOT NULL,
    productVariantId UUID NOT NULL,
    PRIMARY KEY (discountId, productVariantId),
    FOREIGN KEY (discountId) REFERENCES DiscountEntity(id),
    FOREIGN KEY (productVariantId) REFERENCES ProductVariantEntity(id)
);
CREATE INDEX discountToProductVariantEntity_productVariantId ON DiscountToProductVariantEntity (productVariantId);

CREATE TABLE DiscountUsageEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    discountId UUID NOT NULL,
    userId UUID NOT NULL,
    usages BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (discountId) REFERENCES DiscountEntity(id),
    FOREIGN KEY (userId) REFERENCES UserEntity(id),
    UNIQUE (discountId, userId)
);
CREATE INDEX discountUsageEntity_userId ON DiscountUsageEntity (userId);

-- a redemption increments the coupon's usages in one UPDATE (a concurrent redemption waits for the row lock) and
-- fails beyond maxUsages
CREATE OR REPLACE FUNCTION update_coupon_usages()
RETURNS TRIGGER AS $$
DECLARE
    current_usages INTEGER;
    max_usages INTEGER;
BEGIN
    UPDATE CouponEntity
    SET usages = usages + 1
    WHERE id = NEW.couponId
    RETURNING usages, maxUsages
    INTO current_usages, max_usages;

    IF max_usages IS NOT NULL AND current_usages > max_usages THEN
        RAISE EXCEPTION 'Coupon has been used too often';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER before_insert_update_coupon_usages
BEFORE INSERT ON CouponRedemptionEntity
FOR EACH ROW
EXECUTE FUNCTION update_coupon_usages();

-- a user's usages of a discount stay within maxUsagesPerUser
CREATE OR REPLACE FUNCTION check_discount_usages()
RETURNS TRIGGER AS $$
DECLARE
    max_usages_per_user INTEGER;
BEGIN
    IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND NEW.usages > OLD.usages) THEN
        SELECT maxUsagesPerUser
        INTO max_usages_per_user
        FROM DiscountEntity
        WHERE id = NEW.discountId;

        IF max_usages_per_user IS NOT NULL AND max_usages_per_user < NEW.usages THEN
            RAISE EXCEPTION 'The user has applied the discount too often';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER check_discount_usages_trigger
BEFORE INSERT OR UPDATE OF usages ON DiscountUsageEntity
FOR EACH ROW
EXECUTE FUNCTION check_discount_usages();
