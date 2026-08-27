-- V9 — Soft delete for user accounts (admin "deactivate" action).
-- NULL = active account; a timestamp = deactivated (cannot log in, hidden from
-- default lists, but the row and all foreign-key references stay intact).
ALTER TABLE cafe_user
    ADD COLUMN deleted_at TIMESTAMP NULL;