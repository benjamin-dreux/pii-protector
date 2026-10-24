IF DB_ID(N'pii_protector') IS NULL
    CREATE DATABASE pii_protector;
GO

USE pii_protector;
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.extended_properties WHERE class = 0 AND name = N'ADDRESS_BOOK_SAMPLES_READY'
)
BEGIN
    DROP TABLE IF EXISTS dbo.contact_relationships;
    DROP TABLE IF EXISTS dbo.contact_emails;
    DROP TABLE IF EXISTS dbo.contact_phones;
    DROP TABLE IF EXISTS dbo.contact_addresses;
    DROP TABLE IF EXISTS dbo.contacts;

    CREATE TABLE dbo.contacts (
        contact_id BIGINT NOT NULL PRIMARY KEY,
        display_name VARCHAR(512) NULL,
        date_of_birth VARCHAR(128) NULL,
        preferred_language VARCHAR(10) NOT NULL,
        contact_status VARCHAR(16) NOT NULL,
        created_at DATETIME2 NOT NULL,
        updated_at DATETIME2 NOT NULL,
        CONSTRAINT contacts_status_ck CHECK (contact_status IN ('ACTIVE', 'ARCHIVED', 'PENDING'))
    );
    CREATE TABLE dbo.contact_addresses (
        address_id BIGINT NOT NULL PRIMARY KEY,
        contact_id BIGINT NOT NULL REFERENCES dbo.contacts(contact_id),
        address_type VARCHAR(16) NOT NULL,
        street VARCHAR(512) NULL,
        locality VARCHAR(256) NULL,
        postal_code VARCHAR(128) NULL,
        country_code CHAR(2) NOT NULL,
        is_primary BIT NOT NULL,
        valid_from DATE NOT NULL,
        valid_to DATE NULL,
        CONSTRAINT contact_addresses_type_ck CHECK (address_type IN ('HOME', 'WORK', 'OTHER')),
        CONSTRAINT contact_addresses_date_ck CHECK (valid_to IS NULL OR valid_to >= valid_from)
    );
    CREATE TABLE dbo.contact_phones (
        phone_id BIGINT NOT NULL PRIMARY KEY,
        contact_id BIGINT NOT NULL REFERENCES dbo.contacts(contact_id),
        phone_type VARCHAR(16) NOT NULL,
        phone_number VARCHAR(256) NULL,
        extension VARCHAR(128) NULL,
        is_primary BIT NOT NULL,
        verified_at DATETIME2 NULL,
        CONSTRAINT contact_phones_type_ck CHECK (phone_type IN ('MOBILE', 'HOME', 'WORK', 'OTHER'))
    );
    CREATE TABLE dbo.contact_emails (
        email_id BIGINT NOT NULL PRIMARY KEY,
        contact_id BIGINT NOT NULL REFERENCES dbo.contacts(contact_id),
        email_type VARCHAR(16) NOT NULL,
        email_address VARCHAR(512) NULL,
        is_primary BIT NOT NULL,
        is_verified BIT NOT NULL,
        created_at DATETIME2 NOT NULL,
        CONSTRAINT contact_emails_type_ck CHECK (email_type IN ('PERSONAL', 'WORK', 'OTHER'))
    );
    CREATE TABLE dbo.contact_relationships (
        relationship_id BIGINT NOT NULL PRIMARY KEY,
        contact_id BIGINT NOT NULL REFERENCES dbo.contacts(contact_id),
        related_contact_id BIGINT NOT NULL REFERENCES dbo.contacts(contact_id),
        relationship_type VARCHAR(16) NOT NULL,
        notes VARCHAR(2048) NULL,
        created_at DATETIME2 NOT NULL,
        CONSTRAINT contact_relationships_not_self_ck CHECK (contact_id <> related_contact_id),
        CONSTRAINT contact_relationships_type_ck
            CHECK (relationship_type IN ('FAMILY', 'FRIEND', 'COLLEAGUE', 'OTHER')),
        CONSTRAINT contact_relationships_pair_uq UNIQUE (contact_id, related_contact_id)
    );

    SELECT ROW_NUMBER() OVER (
        ORDER BY d0.n, d1.n, d2.n, d3.n, d4.n, d5.n, d6.n
    ) AS id
    INTO #numbers
    FROM (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS d0(n)
    CROSS JOIN (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS d1(n)
    CROSS JOIN (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS d2(n)
    CROSS JOIN (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS d3(n)
    CROSS JOIN (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS d4(n)
    CROSS JOIN (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS d5(n)
    CROSS JOIN (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS d6(n);

    INSERT INTO dbo.contacts
    SELECT id, CONCAT('Contact ', id),
           CONVERT(VARCHAR(10), DATEADD(DAY, CONVERT(INT, id % 15000), CONVERT(DATE, '1980-01-01')), 23),
           CASE id % 3 WHEN 0 THEN 'fr-CA' WHEN 1 THEN 'en-US' ELSE 'es-MX' END,
           CASE WHEN id % 20 = 0 THEN 'PENDING' WHEN id % 10 = 0 THEN 'ARCHIVED' ELSE 'ACTIVE' END,
           DATEADD(MILLISECOND, CONVERT(INT, id % 200000000), CONVERT(DATETIME2, '2020-01-01')),
           DATEADD(MILLISECOND, CONVERT(INT, id % 200000000), CONVERT(DATETIME2, '2020-01-01'))
    FROM #numbers;

    INSERT INTO dbo.contact_addresses
    SELECT id, id,
           CASE id % 3 WHEN 0 THEN 'WORK' WHEN 1 THEN 'HOME' ELSE 'OTHER' END,
           CONCAT('Street ', id),
           CONCAT('City ', id % 100000),
           CONCAT('T', RIGHT(CONCAT('00000', id % 100000), 5)),
           CASE id % 4 WHEN 0 THEN 'CA' WHEN 1 THEN 'US' WHEN 2 THEN 'FR' ELSE 'GB' END,
           1,
           DATEADD(DAY, CONVERT(INT, id % 2500), CONVERT(DATE, '2018-01-01')),
           NULL
    FROM #numbers;

    INSERT INTO dbo.contact_phones
    SELECT id, id,
           CASE id % 4 WHEN 0 THEN 'WORK' WHEN 1 THEN 'MOBILE' WHEN 2 THEN 'HOME' ELSE 'OTHER' END,
           CONCAT('+1-555-010-', RIGHT(CONCAT('0000', id % 10000), 4)),
           RIGHT(CONCAT('000', id % 1000), 3), 1,
           CASE WHEN id % 5 = 0 THEN NULL
                ELSE DATEADD(MILLISECOND, CONVERT(INT, id % 100000000), CONVERT(DATETIME2, '2022-01-01'))
           END
    FROM #numbers;

    INSERT INTO dbo.contact_emails
    SELECT id, id,
           CASE id % 3 WHEN 0 THEN 'WORK' WHEN 1 THEN 'PERSONAL' ELSE 'OTHER' END,
           CONCAT('contact', id, '@example.test'), 1, CASE WHEN id % 5 = 0 THEN 0 ELSE 1 END,
           DATEADD(MILLISECOND, CONVERT(INT, id % 150000000), CONVERT(DATETIME2, '2021-01-01'))
    FROM #numbers;

    INSERT INTO dbo.contact_relationships
    SELECT id, id, CASE WHEN id = 10000000 THEN 1 ELSE id + 1 END,
           CASE id % 4 WHEN 0 THEN 'COLLEAGUE' WHEN 1 THEN 'FAMILY'
                       WHEN 2 THEN 'FRIEND' ELSE 'OTHER' END,
           CONCAT('Synthetic relationship note ', id),
           DATEADD(MILLISECOND, CONVERT(INT, id % 50000000), CONVERT(DATETIME2, '2023-01-01'))
    FROM #numbers;

    CREATE INDEX contact_addresses_contact_idx ON dbo.contact_addresses(contact_id);
    CREATE INDEX contact_phones_contact_idx ON dbo.contact_phones(contact_id);
    CREATE INDEX contact_emails_contact_idx ON dbo.contact_emails(contact_id);
    CREATE INDEX contact_relationships_related_idx ON dbo.contact_relationships(related_contact_id);

    EXEC sys.sp_addextendedproperty
        @name = N'ADDRESS_BOOK_SAMPLES_READY',
        @value = N'Five related synthetic address-book tables, ten million rows each';
END;
GO
