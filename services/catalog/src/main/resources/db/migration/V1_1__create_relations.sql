CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE CategoryEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    description TEXT NOT NULL
);

-- one table for all characteristic types (discriminator)
CREATE TABLE CategoryCharacteristicEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    discriminator TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    unit TEXT,
    categoryId UUID NOT NULL,
    FOREIGN KEY (categoryId) REFERENCES CategoryEntity(id)
);

CREATE TABLE ProductEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    internalName TEXT NOT NULL,
    isPubliclyVisible BOOLEAN NOT NULL,
    defaultVariantId UUID
);

CREATE TABLE ProductVariantEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    isPubliclyVisible BOOLEAN NOT NULL,
    productId UUID NOT NULL,
    currentVersion UUID,
    FOREIGN KEY (productId) REFERENCES ProductEntity(id)
);
CREATE INDEX productVariantEntity_productId ON ProductVariantEntity (productId);

CREATE TABLE TaxRateEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE ProductVariantVersionEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    version INTEGER NOT NULL,
    retailPrice INTEGER NOT NULL,
    createdAt TIMESTAMPTZ NOT NULL,
    canBeReturnedForDays INTEGER,
    productVariantId UUID NOT NULL,
    taxRateId UUID NOT NULL,
    weight DOUBLE PRECISION DEFAULT 0,
    FOREIGN KEY (productVariantId) REFERENCES ProductVariantEntity(id),
    FOREIGN KEY (taxRateId) REFERENCES TaxRateEntity(id)
);
CREATE INDEX productVariantVersionEntity_variant_version ON ProductVariantVersionEntity (productVariantId, version);

ALTER TABLE ProductEntity ADD FOREIGN KEY (defaultVariantId) REFERENCES ProductVariantEntity(id);
ALTER TABLE ProductVariantEntity ADD FOREIGN KEY (currentVersion) REFERENCES ProductVariantVersionEntity(id);

CREATE TABLE ProductToCategoryEntity (
    productId UUID NOT NULL,
    categoryId UUID NOT NULL,
    PRIMARY KEY (productId, categoryId),
    FOREIGN KEY (productId) REFERENCES ProductEntity(id),
    FOREIGN KEY (categoryId) REFERENCES CategoryEntity(id)
);
CREATE INDEX productToCategoryEntity_categoryId ON ProductToCategoryEntity (categoryId);

-- one table for all value types (discriminator)
CREATE TABLE CategoryCharacteristicValueEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    discriminator TEXT NOT NULL,
    stringValue TEXT,
    doubleValue DOUBLE PRECISION,
    categoryCharacteristicId UUID NOT NULL,
    productVariantVersionId UUID NOT NULL,
    FOREIGN KEY (categoryCharacteristicId) REFERENCES CategoryCharacteristicEntity(id),
    FOREIGN KEY (productVariantVersionId) REFERENCES ProductVariantVersionEntity(id),
    UNIQUE (categoryCharacteristicId, productVariantVersionId)
);
CREATE INDEX categoryCharacteristicValueEntity_version ON CategoryCharacteristicValueEntity (productVariantVersionId);

-- a value belongs to a characteristic of one of the categories of the version's product
CREATE OR REPLACE FUNCTION check_category_value_product_category_fn()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM ProductVariantVersionEntity pvve
        JOIN ProductVariantEntity pve ON pvve.productVariantId = pve.id
        JOIN ProductToCategoryEntity pce ON pve.productId = pce.productId
        JOIN CategoryCharacteristicEntity cce ON cce.categoryId = pce.categoryId AND cce.id = NEW.categoryCharacteristicId
        WHERE pvve.id = NEW.productVariantVersionId
    ) THEN
        RAISE EXCEPTION 'Invalid association between productVariantVersionId and categoryId';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_check_category_value_product_category
BEFORE INSERT OR UPDATE ON CategoryCharacteristicValueEntity
FOR EACH ROW
EXECUTE FUNCTION check_category_value_product_category_fn();

CREATE TABLE MediaEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE ProductVariantVersionToMediaEntity (
    productVariantVersionId UUID NOT NULL,
    mediaId UUID NOT NULL,
    PRIMARY KEY (productVariantVersionId, mediaId),
    FOREIGN KEY (productVariantVersionId) REFERENCES ProductVariantVersionEntity(id),
    FOREIGN KEY (mediaId) REFERENCES MediaEntity(id)
);
