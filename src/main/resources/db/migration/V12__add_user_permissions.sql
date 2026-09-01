CREATE TABLE cafe_user_permission (
    user_id    BIGINT      NOT NULL REFERENCES cafe_user(id) ON DELETE CASCADE,
    permission VARCHAR(63) NOT NULL,
    PRIMARY KEY (user_id, permission)
);

-- Promote the seeded root admin to the SUPER_ADMIN tier. SUPER_ADMIN always
-- retains full access and is the only tier that can grant the AI permissions
-- (ai-agentic-actions, ai-coding-tool) to others. Scoped admins use the plain
-- ADMIN role + rows in cafe_user_permission.
UPDATE cafe_user SET role = 'SUPER_ADMIN' WHERE username = 'admin';
