-- Seed one initial STAFF user.
-- Password: changeme123 (BCrypt-hashed, placeholder)
-- must_change_password forces this to be changed on first login.
INSERT INTO cafe_user (username, password, role, must_change_password)
VALUES ('staff', '$2b$10$pBnYk3wGtTgFwy7uiOgotOWr6Vl4d5Ryexq1jMRI.cRLDxL4a39X.', 'STAFF', TRUE);