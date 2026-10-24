WHENEVER SQLERROR EXIT SQL.SQLCODE;
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER USER pii QUOTA UNLIMITED ON USERS;
ALTER SESSION SET CURRENT_SCHEMA = pii;

CREATE TABLE contacts (
    contact_id NUMBER(10) PRIMARY KEY,
    display_name VARCHAR2(512),
    date_of_birth VARCHAR2(128),
    preferred_language VARCHAR2(10) NOT NULL,
    contact_status VARCHAR2(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT contacts_status_ck CHECK (contact_status IN ('ACTIVE', 'ARCHIVED', 'PENDING'))
);
INSERT /*+ APPEND */ INTO contacts
SELECT LEVEL, 'Contact ' || LEVEL,
       TO_CHAR(DATE '1980-01-01' + MOD(LEVEL, 15000), 'YYYY-MM-DD'),
       CASE MOD(LEVEL, 3) WHEN 0 THEN 'fr-CA' WHEN 1 THEN 'en-US' ELSE 'es-MX' END,
       CASE WHEN MOD(LEVEL, 20) = 0 THEN 'PENDING'
            WHEN MOD(LEVEL, 10) = 0 THEN 'ARCHIVED' ELSE 'ACTIVE' END,
       TO_TIMESTAMP_TZ('2020-01-01 00:00:00 UTC', 'YYYY-MM-DD HH24:MI:SS TZR')
           + NUMTODSINTERVAL(MOD(LEVEL, 200000000), 'MILLISECOND'),
       TO_TIMESTAMP_TZ('2020-01-01 00:00:00 UTC', 'YYYY-MM-DD HH24:MI:SS TZR')
           + NUMTODSINTERVAL(MOD(LEVEL, 200000000), 'MILLISECOND')
FROM dual CONNECT BY LEVEL <= 10000000;
COMMIT;

CREATE TABLE contact_addresses (
    address_id NUMBER(10) PRIMARY KEY,
    contact_id NUMBER(10) NOT NULL REFERENCES contacts(contact_id),
    address_type VARCHAR2(16) NOT NULL,
    street VARCHAR2(512),
    locality VARCHAR2(256),
    postal_code VARCHAR2(128),
    country_code CHAR(2) NOT NULL,
    is_primary NUMBER(1) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    CONSTRAINT contact_addresses_type_ck CHECK (address_type IN ('HOME', 'WORK', 'OTHER')),
    CONSTRAINT contact_addresses_date_ck CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT contact_addresses_primary_ck CHECK (is_primary IN (0, 1))
);
INSERT /*+ APPEND */ INTO contact_addresses
SELECT LEVEL, LEVEL,
       CASE MOD(LEVEL, 3) WHEN 0 THEN 'WORK' WHEN 1 THEN 'HOME' ELSE 'OTHER' END,
       'Street ' || LEVEL,
       'City ' || MOD(LEVEL, 100000),
       'T' || LPAD(TO_CHAR(MOD(LEVEL, 100000)), 5, '0'),
       CASE MOD(LEVEL, 4) WHEN 0 THEN 'CA' WHEN 1 THEN 'US' WHEN 2 THEN 'FR' ELSE 'GB' END,
       1, DATE '2018-01-01' + MOD(LEVEL, 2500), NULL
FROM dual CONNECT BY LEVEL <= 10000000;
COMMIT;

CREATE TABLE contact_phones (
    phone_id NUMBER(10) PRIMARY KEY,
    contact_id NUMBER(10) NOT NULL REFERENCES contacts(contact_id),
    phone_type VARCHAR2(16) NOT NULL,
    phone_number VARCHAR2(256),
    extension VARCHAR2(128),
    is_primary NUMBER(1) NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT contact_phones_type_ck CHECK (phone_type IN ('MOBILE', 'HOME', 'WORK', 'OTHER')),
    CONSTRAINT contact_phones_primary_ck CHECK (is_primary IN (0, 1))
);
INSERT /*+ APPEND */ INTO contact_phones
SELECT LEVEL, LEVEL,
       CASE MOD(LEVEL, 4) WHEN 0 THEN 'WORK' WHEN 1 THEN 'MOBILE'
                         WHEN 2 THEN 'HOME' ELSE 'OTHER' END,
       '+1-555-010-' || LPAD(TO_CHAR(MOD(LEVEL, 10000)), 4, '0'),
       LPAD(TO_CHAR(MOD(LEVEL, 1000)), 3, '0'), 1,
       CASE WHEN MOD(LEVEL, 5) = 0 THEN NULL
            ELSE TO_TIMESTAMP_TZ('2022-01-01 00:00:00 UTC', 'YYYY-MM-DD HH24:MI:SS TZR')
                 + NUMTODSINTERVAL(MOD(LEVEL, 100000000), 'MILLISECOND')
       END
FROM dual CONNECT BY LEVEL <= 10000000;
COMMIT;

CREATE TABLE contact_emails (
    email_id NUMBER(10) PRIMARY KEY,
    contact_id NUMBER(10) NOT NULL REFERENCES contacts(contact_id),
    email_type VARCHAR2(16) NOT NULL,
    email_address VARCHAR2(512),
    is_primary NUMBER(1) NOT NULL,
    is_verified NUMBER(1) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT contact_emails_type_ck CHECK (email_type IN ('PERSONAL', 'WORK', 'OTHER')),
    CONSTRAINT contact_emails_primary_ck CHECK (is_primary IN (0, 1)),
    CONSTRAINT contact_emails_verified_ck CHECK (is_verified IN (0, 1))
);
INSERT /*+ APPEND */ INTO contact_emails
SELECT LEVEL, LEVEL,
       CASE MOD(LEVEL, 3) WHEN 0 THEN 'WORK' WHEN 1 THEN 'PERSONAL' ELSE 'OTHER' END,
       'contact' || LEVEL || '@example.test', 1,
       CASE WHEN MOD(LEVEL, 5) = 0 THEN 0 ELSE 1 END,
       TO_TIMESTAMP_TZ('2021-01-01 00:00:00 UTC', 'YYYY-MM-DD HH24:MI:SS TZR')
           + NUMTODSINTERVAL(MOD(LEVEL, 150000000), 'MILLISECOND')
FROM dual CONNECT BY LEVEL <= 10000000;
COMMIT;

CREATE TABLE contact_relationships (
    relationship_id NUMBER(10) PRIMARY KEY,
    contact_id NUMBER(10) NOT NULL REFERENCES contacts(contact_id),
    related_contact_id NUMBER(10) NOT NULL REFERENCES contacts(contact_id),
    relationship_type VARCHAR2(16) NOT NULL,
    notes VARCHAR2(2048),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT contact_relationships_not_self_ck CHECK (contact_id <> related_contact_id),
    CONSTRAINT contact_relationships_type_ck
        CHECK (relationship_type IN ('FAMILY', 'FRIEND', 'COLLEAGUE', 'OTHER')),
    CONSTRAINT contact_relationships_pair_uq UNIQUE (contact_id, related_contact_id)
);
INSERT /*+ APPEND */ INTO contact_relationships
SELECT LEVEL, LEVEL, CASE WHEN LEVEL = 10000000 THEN 1 ELSE LEVEL + 1 END,
       CASE MOD(LEVEL, 4) WHEN 0 THEN 'COLLEAGUE' WHEN 1 THEN 'FAMILY'
                         WHEN 2 THEN 'FRIEND' ELSE 'OTHER' END,
       'Synthetic relationship note ' || LEVEL,
       TO_TIMESTAMP_TZ('2023-01-01 00:00:00 UTC', 'YYYY-MM-DD HH24:MI:SS TZR')
           + NUMTODSINTERVAL(MOD(LEVEL, 50000000), 'MILLISECOND')
FROM dual CONNECT BY LEVEL <= 10000000;
COMMIT;

CREATE INDEX contact_addresses_contact_idx ON contact_addresses(contact_id);
CREATE INDEX contact_phones_contact_idx ON contact_phones(contact_id);
CREATE INDEX contact_emails_contact_idx ON contact_emails(contact_id);
CREATE INDEX contact_relationships_related_idx ON contact_relationships(related_contact_id);
