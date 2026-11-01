CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE UserEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE NotificationEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    dateSent TIMESTAMPTZ NOT NULL,
    dateRead TIMESTAMPTZ,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    userId UUID NOT NULL,
    FOREIGN KEY (userId) REFERENCES UserEntity(id)
);
CREATE INDEX notificationEntity_userId_id ON NotificationEntity (userId, id);
