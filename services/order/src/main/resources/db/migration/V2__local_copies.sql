-- Local copies for createOrder (docs/MECHANISMS.md), only written and read when misarch.mechanisms.local-copies=true
-- (env MISARCH_LOCAL_COPIES); otherwise empty, and createOrder calls discount and shipment as before.

CREATE TABLE LocalCopyDiscountEntity (
    id UUID PRIMARY KEY,
    discount DOUBLE PRECISION NOT NULL,
    validFrom TIMESTAMPTZ NOT NULL,
    validUntil TIMESTAMPTZ NOT NULL,
    minOrderAmount BIGINT,
    appliesVariantIds UUID[] NOT NULL,
    appliesProductIds UUID[] NOT NULL,
    appliesCategoryIds UUID[] NOT NULL
);

CREATE TABLE LocalCopyCouponEntity (
    id UUID PRIMARY KEY,
    discountId UUID NOT NULL
);

CREATE TABLE LocalCopyProductEntity (
    id UUID PRIMARY KEY,
    categoryIds UUID[] NOT NULL
);

CREATE TABLE LocalCopyProductVariantEntity (
    id UUID PRIMARY KEY,
    productId UUID NOT NULL
);

CREATE TABLE LocalCopyShipmentMethodEntity (
    id UUID PRIMARY KEY,
    baseFees BIGINT NOT NULL,
    feesPerItem BIGINT NOT NULL,
    feesPerKg BIGINT NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE LocalCopyProductVariantVersionEntity (
    id UUID PRIMARY KEY,
    weight DOUBLE PRECISION NOT NULL
);
