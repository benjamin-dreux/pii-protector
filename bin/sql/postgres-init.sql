CREATE TABLE contacts (
    contact_id BIGINT PRIMARY KEY,
    display_name VARCHAR(512),
    date_of_birth VARCHAR(128),
    preferred_language VARCHAR(10) NOT NULL,
    contact_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT contacts_status_ck CHECK (contact_status IN ('ACTIVE', 'ARCHIVED', 'PENDING'))
);
INSERT INTO contacts
SELECT id,
       'Contact ' || id,
       TO_CHAR(DATE '1980-01-01' + (id % 15000)::INTEGER, 'YYYY-MM-DD'),
       CASE WHEN id % 3 = 0 THEN 'fr-CA' WHEN id % 3 = 1 THEN 'en-US' ELSE 'es-MX' END,
       CASE WHEN id % 20 = 0 THEN 'PENDING' WHEN id % 10 = 0 THEN 'ARCHIVED' ELSE 'ACTIVE' END,
       TIMESTAMPTZ '2020-01-01 00:00:00+00' + (id % 200000000) * INTERVAL '1 millisecond',
       TIMESTAMPTZ '2020-01-01 00:00:00+00' + (id % 200000000) * INTERVAL '1 millisecond'
FROM generate_series(1, 10000000) AS series(id);

CREATE TABLE contact_addresses (
    address_id BIGINT PRIMARY KEY,
    contact_id BIGINT NOT NULL REFERENCES contacts(contact_id),
    address_type VARCHAR(16) NOT NULL,
    street VARCHAR(512),
    locality VARCHAR(256),
    postal_code VARCHAR(128),
    country_code CHAR(2) NOT NULL,
    is_primary BOOLEAN NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    CONSTRAINT contact_addresses_type_ck CHECK (address_type IN ('HOME', 'WORK', 'OTHER')),
    CONSTRAINT contact_addresses_date_ck CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
INSERT INTO contact_addresses
SELECT id, id,
       CASE WHEN id % 3 = 0 THEN 'WORK' WHEN id % 3 = 1 THEN 'HOME' ELSE 'OTHER' END,
       'Street ' || id,
       'City ' || (id % 100000),
       'T' || LPAD((id % 100000)::TEXT, 5, '0'),
       CASE WHEN id % 4 = 0 THEN 'CA' WHEN id % 4 = 1 THEN 'US'
            WHEN id % 4 = 2 THEN 'FR' ELSE 'GB' END,
       TRUE,
       DATE '2018-01-01' + (id % 2500)::INTEGER,
       NULL
FROM generate_series(1, 10000000) AS series(id);

CREATE TABLE contact_phones (
    phone_id BIGINT PRIMARY KEY,
    contact_id BIGINT NOT NULL REFERENCES contacts(contact_id),
    phone_type VARCHAR(16) NOT NULL,
    phone_number VARCHAR(256),
    extension VARCHAR(128),
    is_primary BOOLEAN NOT NULL,
    verified_at TIMESTAMPTZ,
    CONSTRAINT contact_phones_type_ck CHECK (phone_type IN ('MOBILE', 'HOME', 'WORK', 'OTHER'))
);
INSERT INTO contact_phones
SELECT id, id,
       CASE WHEN id % 4 = 0 THEN 'WORK' WHEN id % 4 = 1 THEN 'MOBILE'
            WHEN id % 4 = 2 THEN 'HOME' ELSE 'OTHER' END,
       '+1-555-010-' || LPAD((id % 10000)::TEXT, 4, '0'),
       LPAD((id % 1000)::TEXT, 3, '0'), TRUE,
       CASE WHEN id % 5 = 0 THEN NULL
            ELSE TIMESTAMPTZ '2022-01-01 00:00:00+00' + (id % 100000000) * INTERVAL '1 millisecond'
       END
FROM generate_series(1, 10000000) AS series(id);

CREATE TABLE contact_emails (
    email_id BIGINT PRIMARY KEY,
    contact_id BIGINT NOT NULL REFERENCES contacts(contact_id),
    email_type VARCHAR(16) NOT NULL,
    email_address VARCHAR(512),
    is_primary BOOLEAN NOT NULL,
    is_verified BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT contact_emails_type_ck CHECK (email_type IN ('PERSONAL', 'WORK', 'OTHER'))
);
INSERT INTO contact_emails
SELECT id, id,
       CASE WHEN id % 3 = 0 THEN 'WORK' WHEN id % 3 = 1 THEN 'PERSONAL' ELSE 'OTHER' END,
       'contact' || id || '@example.test', TRUE, id % 5 <> 0,
       TIMESTAMPTZ '2021-01-01 00:00:00+00' + (id % 150000000) * INTERVAL '1 millisecond'
FROM generate_series(1, 10000000) AS series(id);

CREATE TABLE contact_relationships (
    relationship_id BIGINT PRIMARY KEY,
    contact_id BIGINT NOT NULL REFERENCES contacts(contact_id),
    related_contact_id BIGINT NOT NULL REFERENCES contacts(contact_id),
    relationship_type VARCHAR(16) NOT NULL,
    notes VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT contact_relationships_not_self_ck CHECK (contact_id <> related_contact_id),
    CONSTRAINT contact_relationships_type_ck
        CHECK (relationship_type IN ('FAMILY', 'FRIEND', 'COLLEAGUE', 'OTHER')),
    CONSTRAINT contact_relationships_pair_uq UNIQUE (contact_id, related_contact_id)
);
INSERT INTO contact_relationships
SELECT id, id, CASE WHEN id = 10000000 THEN 1 ELSE id + 1 END,
       CASE WHEN id % 4 = 0 THEN 'COLLEAGUE' WHEN id % 4 = 1 THEN 'FAMILY'
            WHEN id % 4 = 2 THEN 'FRIEND' ELSE 'OTHER' END,
       'Synthetic relationship note ' || id,
       TIMESTAMPTZ '2023-01-01 00:00:00+00' + (id % 50000000) * INTERVAL '1 millisecond'
FROM generate_series(1, 10000000) AS series(id);

CREATE INDEX contact_addresses_contact_idx ON contact_addresses(contact_id);
CREATE INDEX contact_phones_contact_idx ON contact_phones(contact_id);
CREATE INDEX contact_emails_contact_idx ON contact_emails(contact_id);
CREATE INDEX contact_relationships_related_idx ON contact_relationships(related_contact_id);
