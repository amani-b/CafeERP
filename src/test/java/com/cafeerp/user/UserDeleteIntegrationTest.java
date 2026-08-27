package com.cafeerp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.AbstractIntegrationTest;
import com.cafeerp.assistant.AssistantConversationRepository;
import com.cafeerp.assistant.AssistantMessageRepository;
import com.cafeerp.category.Category;
import com.cafeerp.category.CategoryRepository;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuItemRepository;
import com.cafeerp.order.Order;
import com.cafeerp.order.OrderRepository;

/**
 * Full-stack tests for the admin soft/hard user delete actions: real form
 * requests through the MVC + Security stack against the real Flyway schema.
 */
class UserDeleteIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired AssistantMessageRepository messageRepository;
    @Autowired AssistantConversationRepository conversationRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired MenuItemRepository menuItemRepository;
    @Autowired OrderRepository orderRepository;

    private long createUserViaForm(String username) throws Exception {
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);
        mockMvc.perform(post("/users").session(admin).with(csrf())
                        .param("username", username)
                        .param("role", "STAFF")
                        .param("password", "password123"))
                .andExpect(status().is3xxRedirection());
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    @Test
    void softDelete_blocksLoginButPreservesAllData_andIsReversible() throws Exception {
        long id = createUserViaForm("softdeletee");

        // Soft delete (the default action) via a real admin request.
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);
        mockMvc.perform(post("/users/deactivate/" + id).session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // The login is blocked — deactivated user is treated as unknown.
        mockMvc.perform(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders
                        .formLogin().user("softdeletee").password("password123"))
                .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers
                        .unauthenticated());

        // But the DATA is preserved: row still exists with deleted_at stamped.
        Map<String, Object> row = new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .queryForMap("SELECT username, role, password, deleted_at FROM cafe_user WHERE id = ?", id);
        assertThat(row.get("USERNAME")).isEqualTo("softdeletee");
        assertThat(row.get("DELETED_AT")).isNotNull();
        assertThat((String) row.get("PASSWORD")).startsWith("$2"); // hash untouched

        // Hidden from the default list, visible in the inactive view.
        // (Assert on the table rows, not the whole page: the flash success
        // banner legitimately mentions the username.)
        MvcResult activeList = mockMvc.perform(get("/users").session(admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(activeList.getResponse().getContentAsString())
                .doesNotContain("data-username=\"softdeletee\"");

        MvcResult inactiveList = mockMvc.perform(get("/users").session(admin).param("showInactive", "1"))
                .andExpect(status().isOk()).andReturn();
        assertThat(inactiveList.getResponse().getContentAsString())
                .contains("data-username=\"softdeletee\"");

        // Reactivating restores login.
        mockMvc.perform(post("/users/activate/" + id).session(admin).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders
                        .formLogin().user("softdeletee").password("password123"))
                .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers
                        .authenticated().withUsername("softdeletee"));
    }

    @Test
    void nonAdminsAreRejectedServerSideOnDeleteActions() throws Exception {
        long id = createUserViaForm("protectedu");

        // STAFF hitting the destructive endpoints directly — URL rule AND
        // @PreAuthorize must both say no.
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);
        mockMvc.perform(post("/users/deactivate/" + id).session(staff).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/users/delete/" + id).session(staff).with(csrf())
                        .param("confirmName", "protectedu"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/users/activate/" + id).session(staff).with(csrf()))
                .andExpect(status().isForbidden());

        // The admin can still delete them afterwards.
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);
        mockMvc.perform(post("/users/delete/" + id).session(admin).with(csrf())
                        .param("confirmName", "protectedu"))
                .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(id)).isEmpty();
    }

    @Test
    void hardDelete_removesUserAndTheirChatHistory_butKeepsBusinessRecords() throws Exception {
        long orderId = seedBusinessRecord();
        long id = createUserViaForm("harddeleteme");

        // The doomed user chats with the assistant (real request) so chat
        // rows reference them when the hard delete runs.
        MockHttpSession doomed = login("harddeleteme", "password123");
        chat(doomed, "status of order #999999");
        assertThat(messageCountFor(id)).isGreaterThan(0);
        long conversationId = firstConversationIdFor(id);
        assertThat(conversationId).isGreaterThan(0);

        // Wrong confirmation must NOT delete (type-to-confirm, server-side).
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);
        mockMvc.perform(post("/users/delete/" + id).session(admin).with(csrf())
                        .param("confirmName", "wrong-name"))
                .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(id)).isPresent();

        // Correct confirmation deletes the account and their chat history.
        mockMvc.perform(post("/users/delete/" + id).session(admin).with(csrf())
                        .param("confirmName", "harddeleteme"))
                .andExpect(status().is3xxRedirection());

        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cafe_user WHERE id = ?", Long.class, id)).isZero();
        // Assistant history is personal to the account: gone with it.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_message WHERE user_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assistant_conversation WHERE id = ?", Long.class, conversationId)).isZero();
        // Business records are untouched.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cafe_order WHERE id = ?", Long.class, orderId)).isEqualTo(1);
    }

    @Test
    void adminCannotDeleteTheirOwnAccount() throws Exception {
        long adminId = userRepository.findByUsername("admin").orElseThrow().getId();
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);
        mockMvc.perform(post("/users/delete/" + adminId).session(admin).with(csrf())
                        .param("confirmName", "admin"))
                .andExpect(status().is3xxRedirection());
        assertThat(userRepository.findById(adminId)).isPresent();
    }

    // ---- helpers -------------------------------------------------------

    /** Seeds one business record (an order) that hard delete must NOT touch. */
    private long seedBusinessRecord() {
        Category cat = new Category();
        cat.setName("HardDelete-" + System.nanoTime());
        cat.setActive(true);
        cat = categoryRepository.save(cat);

        MenuItem item = new MenuItem();
        item.setName("Espresso");
        item.setPrice(new java.math.BigDecimal("2.50"));
        item.setAvailable(true);
        item.setCategory(cat);
        item = menuItemRepository.save(item);

        Order order = new Order();
        order.addItem(item, 1);
        return orderRepository.save(order).getId();
    }

    private long messageCountFor(long userId) {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .queryForObject("SELECT COUNT(*) FROM assistant_message WHERE user_id = ?",
                        Long.class, userId);
    }

    private long firstConversationIdFor(long userId) {
        var ids = new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .queryForList("SELECT id FROM assistant_conversation WHERE user_id = ?",
                        Long.class, userId);
        return ids.isEmpty() ? 0L : ids.get(0);
    }
}
