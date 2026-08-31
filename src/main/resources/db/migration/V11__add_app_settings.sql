-- Business-level settings (key/value). Phase 1 introduces the business
-- timezone: the single source of truth for rendering every timestamp in the
-- ERP, independent of server or browser clock configuration.
-- "key" is a reserved word in PostgreSQL/H2, hence the double quotes.
CREATE TABLE app_setting (
    "key"   VARCHAR(100) PRIMARY KEY,
    "value" VARCHAR(255) NOT NULL
);

INSERT INTO app_setting ("key", "value")
VALUES ('business.timezone', 'Africa/Addis_Ababa');
