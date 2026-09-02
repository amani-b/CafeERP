package com.cafeerp.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import com.cafeerp.AbstractIntegrationTest;
import com.cafeerp.category.Category;
import com.cafeerp.category.CategoryRepository;
import com.cafeerp.inventory.Inventory;
import com.cafeerp.inventory.InventoryRepository;
import com.cafeerp.menu.MenuItem;
import com.cafeerp.menu.MenuItemRepository;
import com.cafeerp.user.Permission;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;
import com.cafeerp.user.UserSessionLog;
import com.cafeerp.user.UserSessionLogRepository;
import com.cafeerp.user.UserService;

/**
 * Phase 4 (Agentic AI) full-stack tests: permission-scoped tool sets,
 * autonomy setting, the pending-action confirm/cancel flow backed by the AI
 * action audit log, and the login/logout session audit trail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgenticActionsIntegrationTest extends AbstractIntegrationTest {

    private static final String NON_AGENTIC = "agentic_none";
    private static final String AGENTIC = "agentic_owner";

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired UserSessionLogRepository sessionLogRepository;
    @Autowired AssistantActionLogRepository actionLogRepository;
    @Autowired AssistantToolRegistry toolRegistry;
    @Autowired CategoryRepository categoryRepository;
    @Autowired MenuItemRepository menuItemRepository;
    @Autowired InventoryRepository inventoryRepository;

    private String trackedItemName;

    @BeforeAll
    void seedUsersAndTrackedInventoryItem() {
        ensureUser(NON_AGENTIC, Role.ADMIN, Set.of(Permission.INVENTORY));
        ensureUser(AGENTIC, Role.ADMIN, Set.of(Permission.INVENTORY, Permission.AI_AGENTIC_ACTIONS));
        new JdbcTemplate(dataSource).update("UPDATE cafe_user SET must_change_password = FALSE");

        // A tracked inventory item the write tools can act on (idempotent).
        trackedItemName = "Agentic Test Beans";
        if (menuItemRepository.findAll().stream().noneMatch(m -> m.getName().equals(trackedItemName))) {
            Category category = new Category();
            category.setName("Agentic Test Category");
            category.setActive(true);
            category = categoryRepository.save(category);

            MenuItem item = new MenuItem();
            item.setName(trackedItemName);
            item.setPrice(new java.math.BigDecimal("4.50"));
            item.setAvailable(true);
            item.setCategory(category);
            item = menuItemRepository.save(item);

            Inventory inv = new Inventory();
            inv.setMenuItem(item);
            inv.setTrackInventory(true);
            inv.setStockQuantity(10);
            inv.setLowStockThreshold(2);
            inventoryRepository.save(inv);
        }
    }

    private void ensureUser(String username, Role role, Set<Permission> permissions) {
        if (userRepository.findByUsername(username).isEmpty()) {
            User user = new User();
            user.setUsername(username);
            user.setPassword("password123");
            user.setRole(role);
            userService.createUser(user);
        }
        User user = userRepository.findByUsername(username).orElseThrow();
        user.setPermissions(permissions);
        userService.updateUser(user);
    }

    private User user(String username) {
        return userRepository.findByUsername(username).orElseThrow();
    }

    private AssistantActionLog pendingAction(User forUser, String tool, String paramsJson) {
        AssistantActionLog action = new AssistantActionLog();
        action.setUser(forUser);
        action.setTool(tool);
        action.setParamsJson(paramsJson);
        action.setDescription(toolRegistry.describeAction(tool, paramsJson));
        action.setStatus(AssistantActionLog.Status.PENDING_CONFIRMATION);
        return actionLogRepository.save(action);
    }

    private int stockOf(String itemName) {
        return inventoryRepository.findAll().stream()
                .filter(i -> i.getMenuItem().getName().equals(itemName))
                .findFirst().orElseThrow().getStockQuantity();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void toolSetsAreScopedToCallerPermissions() {
        var agenticTools = toolRegistry.allowedToolNamesForUser(user(AGENTIC));
        var plainTools = toolRegistry.allowedToolNamesForUser(user(NON_AGENTIC));

        org.assertj.core.api.Assertions.assertThat(agenticTools)
                .contains("getInventoryLevel", "updateInventory", "getMenuItems")
                .doesNotContain("createOrder", "updateOrderStatus");
        org.assertj.core.api.Assertions.assertThat(plainTools)
                .contains("getInventoryLevel")
                .doesNotContain("updateInventory", "createOrder", "updateOrderStatus");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void writeToolIsDeniedWithoutAgenticPermission() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> toolRegistry.execute("updateInventory",
                "{\"itemName\":\"" + trackedItemName + "\",\"stockQuantity\":5}", user(NON_AGENTIC)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void writeToolExecutesForAgenticUserWithModulePermission() {
        String result = toolRegistry.execute("updateInventory",
                "{\"itemName\":\"" + trackedItemName + "\",\"stockQuantity\":7}", user(AGENTIC));
        org.assertj.core.api.Assertions.assertThat(result).contains("Updated stock");
        org.assertj.core.api.Assertions.assertThat(stockOf(trackedItemName)).isEqualTo(7);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void confirmExecutesPendingActionAndAuditsIt() throws Exception {
        MockHttpSession session = login(AGENTIC, "password123");
        int before = stockOf(trackedItemName);
        AssistantActionLog action = pendingAction(user(AGENTIC), "updateInventory",
                "{\"itemName\":\"" + trackedItemName + "\",\"stockQuantity\":" + (before + 3) + "}");

        mockMvc.perform(post("/assistant/actions/" + action.getId() + "/confirm")
                        .session(session).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").exists());

        org.assertj.core.api.Assertions.assertThat(stockOf(trackedItemName)).isEqualTo(before + 3);
        AssistantActionLog audited = actionLogRepository.findById(action.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(audited.getStatus())
                .isEqualTo(AssistantActionLog.Status.EXECUTED);
        org.assertj.core.api.Assertions.assertThat(audited.getTriggerMode())
                .isEqualTo(AssistantActionLog.TriggerMode.USER_CONFIRMED);
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void cancelLeavesDataUntouched() throws Exception {
        MockHttpSession session = login(AGENTIC, "password123");
        int before = stockOf(trackedItemName);
        AssistantActionLog action = pendingAction(user(AGENTIC), "updateInventory",
                "{\"itemName\":\"" + trackedItemName + "\",\"stockQuantity\":" + (before + 99) + "}");

        mockMvc.perform(post("/assistant/actions/" + action.getId() + "/cancel")
                        .session(session).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of())))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(stockOf(trackedItemName)).isEqualTo(before);
        org.assertj.core.api.Assertions.assertThat(
                actionLogRepository.findById(action.getId()).orElseThrow().getStatus())
                .isEqualTo(AssistantActionLog.Status.CANCELLED);
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void cannotConfirmAnotherUsersPendingAction() throws Exception {
        AssistantActionLog action = pendingAction(user(AGENTIC), "updateInventory",
                "{\"itemName\":\"" + trackedItemName + "\",\"stockQuantity\":1}");
        MockHttpSession otherSession = login(NON_AGENTIC, "password123");

        mockMvc.perform(post("/assistant/actions/" + action.getId() + "/confirm")
                        .session(otherSession).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of())))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(
                actionLogRepository.findById(action.getId()).orElseThrow().getStatus())
                .isEqualTo(AssistantActionLog.Status.PENDING_CONFIRMATION);
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    void pendingActionsEndpointListsOwnPendingActionsOnly() throws Exception {
        MockHttpSession session = login(AGENTIC, "password123");
        AssistantActionLog action = pendingAction(user(AGENTIC), "updateInventory",
                "{\"itemName\":\"" + trackedItemName + "\",\"stockQuantity\":2}");

        mockMvc.perform(get("/assistant/conversations/1/pending-actions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + action.getId() + ")]").exists())
                .andExpect(jsonPath("$[?(@.tool == 'updateInventory')]").exists());

        // Not visible to another user.
        MockHttpSession other = login(NON_AGENTIC, "password123");
        mockMvc.perform(get("/assistant/conversations/1/pending-actions").session(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + action.getId() + ")]").doesNotExist());
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    void autonomyDefaultsToAlwaysConfirmAndCanBeChangedPerSession() throws Exception {
        MockHttpSession session = login(AGENTIC, "password123");

        mockMvc.perform(get("/assistant/autonomy").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ALWAYS_CONFIRM"));

        mockMvc.perform(post("/assistant/autonomy").session(session).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("mode", "AUTO_LOW_RISK"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("AUTO_LOW_RISK"));

        MockHttpSession fresh = login(AGENTIC, "password123");
        mockMvc.perform(get("/assistant/autonomy").session(fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ALWAYS_CONFIRM"));
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    void loginAndLogoutAreRecorded() throws Exception {
        MockHttpSession session = login(NON_AGENTIC, "password123");

        mockMvc.perform(post("/logout").session(session).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().is3xxRedirection());

        var events = sessionLogRepository
                .findByUsernameIgnoreCaseOrderByOccurredAtDescIdDesc(
                        NON_AGENTIC, org.springframework.data.domain.PageRequest.of(0, 10));
        org.assertj.core.api.Assertions.assertThat(events.get(0).getEvent())
                .isEqualTo(UserSessionLog.Event.LOGOUT);
        org.assertj.core.api.Assertions.assertThat(events.stream()
                .map(UserSessionLog::getEvent))
                .contains(UserSessionLog.Event.LOGIN);
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    void adminActionLogPageRenders() throws Exception {
        MockHttpSession session = login(AGENTIC, "password123");
        mockMvc.perform(get("/admin/assistant/actions").session(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("assistant/admin-actions"));
    }
}
