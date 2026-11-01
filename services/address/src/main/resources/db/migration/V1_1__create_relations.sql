CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE UserEntity (
    id UUID PRIMARY KEY
);

-- a user address has a userId, a vendor address has none; the current vendor address has the highest version
CREATE TABLE AddressEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    street1 TEXT NOT NULL,
    street2 TEXT,
    city TEXT NOT NULL,
    postalCode TEXT NOT NULL,
    country TEXT NOT NULL,
    companyName TEXT,
    userId UUID,
    archivedAt TIMESTAMPTZ,
    version BIGSERIAL,
    firstName TEXT,
    lastName TEXT,
    FOREIGN KEY (userId) REFERENCES UserEntity(id),
    CONSTRAINT addressEntity_firstName_lastName_check CHECK ((firstName IS NULL) = (lastName IS NULL))
);
CREATE INDEX addressEntity_userId_id ON AddressEntity (userId, id);
