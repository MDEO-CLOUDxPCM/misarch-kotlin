CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE UserEntity (
    id UUID PRIMARY KEY,
    firstName TEXT NOT NULL,
    lastName TEXT NOT NULL
);

CREATE TABLE UserAddressEntity (
    id UUID PRIMARY KEY,
    userId UUID NOT NULL,
    street1 TEXT NOT NULL,
    street2 TEXT NOT NULL,
    city TEXT NOT NULL,
    postalCode TEXT NOT NULL,
    country TEXT NOT NULL,
    companyName TEXT,
    firstName TEXT,
    lastName TEXT,
    isArchived BOOLEAN NOT NULL DEFAULT false
);

-- the current vendor address has the highest version
CREATE TABLE VendorAddressEntity (
    id UUID PRIMARY KEY,
    street1 TEXT NOT NULL,
    street2 TEXT NOT NULL,
    city TEXT NOT NULL,
    postalCode TEXT NOT NULL,
    country TEXT NOT NULL,
    companyName TEXT,
    version BIGSERIAL
);

CREATE TABLE InvoiceEntity (
    id UUID PRIMARY KEY,
    orderId UUID NOT NULL,
    issuedAt TIMESTAMPTZ NOT NULL,
    content TEXT NOT NULL,
    vatNumber TEXT,
    userAddressId UUID NOT NULL,
    vendorAddressId UUID NOT NULL,
    UNIQUE (orderId)
);
