-- V15 — Phase 9 (performance pass): indexes for the assistant's Phase 4
-- read-access tools and the report range queries.
--
-- getOrderHistory pages newest-first (optionally filtered by status):
--   order by created_at desc / where status = ? order by created_at desc.
CREATE INDEX idx_cafe_order_created_at ON cafe_order(created_at);
CREATE INDEX idx_cafe_order_status_created ON cafe_order(status, created_at);
-- getUserLoginHistory / getUserSessionActivity filter by the username
-- snapshot, newest first (V13 only indexed the user_id FK).
CREATE INDEX idx_user_session_log_username_time ON user_session_log(username, occurred_at);
-- Pending-confirmation lookup filters one user's actions by status.
CREATE INDEX idx_assistant_action_log_user_status ON assistant_action_log(user_id, status);
