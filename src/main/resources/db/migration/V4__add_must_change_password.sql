ALTER TABLE cafe_user
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- The seeded admin from V2 has the placeholder password "changeme123";
-- force them to change it on first login.
UPDATE cafe_user
SET must_change_password = TRUE
WHERE username = 'admin';