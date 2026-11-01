CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE TaxRateEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    currentVersionId UUID
);

CREATE TABLE TaxRateVersionEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    rate DOUBLE PRECISION NOT NULL,
    createdAt TIMESTAMPTZ NOT NULL,
    version INTEGER NOT NULL,
    taxRateId UUID,
    FOREIGN KEY (taxRateId) REFERENCES TaxRateEntity(id)
);

ALTER TABLE TaxRateEntity ADD FOREIGN KEY (currentVersionId) REFERENCES TaxRateVersionEntity(id);
