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

CREATE TABLE ReviewEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    userId UUID NOT NULL,
    productVariantId UUID NOT NULL,
    productId UUID NOT NULL,
    body TEXT NOT NULL,
    rating TEXT NOT NULL CHECK (rating IN ('ONE_STARS', 'TWO_STARS', 'THREE_STARS', 'FOUR_STARS', 'FIVE_STARS')),
    createdAt TIMESTAMPTZ NOT NULL,
    lastUpdatedAt TIMESTAMPTZ NOT NULL,
    isVisible BOOLEAN NOT NULL,
    -- the rating's number of stars, for the RATING order and the average rating
    stars SMALLINT GENERATED ALWAYS AS (CASE rating
        WHEN 'ONE_STARS' THEN 1
        WHEN 'TWO_STARS' THEN 2
        WHEN 'THREE_STARS' THEN 3
        WHEN 'FOUR_STARS' THEN 4
        WHEN 'FIVE_STARS' THEN 5 END) STORED,
    UNIQUE (userId, productVariantId)
);
CREATE INDEX reviewEntity_productVariantId ON ReviewEntity (productVariantId);
CREATE INDEX reviewEntity_productId ON ReviewEntity (productId);
