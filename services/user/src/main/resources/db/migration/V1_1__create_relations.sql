CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE UserEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username TEXT NOT NULL,
    firstName TEXT NOT NULL,
    gender TEXT,
    birthday TIMESTAMPTZ,
    dateJoined TIMESTAMPTZ NOT NULL,
    lastName TEXT NOT NULL
);
