package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time clean slate for chat-history testing (V14).
 *
 * <p>Hard-deletes EVERYTHING in the three chat/log stores, across all
 * tiers/users:
 * <ol>
 *   <li>{@code assistant_message} — every chatroom message (the sidebar
 *       history store and the backing rows of the Assistant Logs views);</li>
 *   <li>{@code assistant_action_log} — the separate audit log for
 *       agentic/tool-driven actions (pending confirmations included);</li>
 *   <li>{@code assistant_conversation} — every chatroom entry itself.</li>
 * </ol>
 *
 * <p>Real deletes, not soft-archives. Explicitly OUT of scope (untouched):
 * user accounts, permissions, ERP business data (orders, inventory, menu,
 * reports, settings), the login/session audit ({@code user_session_log}),
 * and every other table.
 *
 * <p>Delete order is FK-safe: messages reference conversations
 * ({@code fk_assistant_message_conversation}), so messages go first;
 * the action log carries no FK to conversations (plain {@code BIGINT}
 * stamp), only to {@code cafe_user} — and users are never deleted, so no
 * constraint can fire.
 *
 * <p>Logs before/after counts per store so the wipe is verifiable, not
 * assumed. Runs exactly once via Flyway versioning.
 */
public class V14__Wipe_chat_history extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V14__Wipe_chat_history.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement stmt = connection.createStatement()) {
            long messagesBefore = count(stmt, "assistant_message");
            long actionsBefore = count(stmt, "assistant_action_log");
            long conversationsBefore = count(stmt, "assistant_conversation");
            log.warn("Chat history wipe starting: assistant_message={} row(s), "
                            + "assistant_action_log={} row(s), assistant_conversation={} row(s)",
                    messagesBefore, actionsBefore, conversationsBefore);

            // FK-safe order: messages first (they reference conversations),
            // then the action log, then the conversations themselves.
            long messagesDeleted = stmt.executeUpdate("DELETE FROM assistant_message");
            long actionsDeleted = stmt.executeUpdate("DELETE FROM assistant_action_log");
            long conversationsDeleted = stmt.executeUpdate("DELETE FROM assistant_conversation");

            long messagesAfter = count(stmt, "assistant_message");
            long actionsAfter = count(stmt, "assistant_action_log");
            long conversationsAfter = count(stmt, "assistant_conversation");
            log.warn("Chat history wipe complete: messages deleted={} (before={} after={}), "
                            + "action-log entries deleted={} (before={} after={}), "
                            + "conversations deleted={} (before={} after={})",
                    messagesDeleted, messagesBefore, messagesAfter,
                    actionsDeleted, actionsBefore, actionsAfter,
                    conversationsDeleted, conversationsBefore, conversationsAfter);
        }
    }

    private static long count(Statement stmt, String table) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
