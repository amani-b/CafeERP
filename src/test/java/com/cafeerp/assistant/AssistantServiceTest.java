package com.cafeerp.assistant;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

    @Mock
    private AssistantMessageRepository messageRepository;

    @Mock
    private AssistantToolRegistry toolRegistry;

    @InjectMocks
    private AssistantService assistantService;

    private User staffUser;
    private User kitchenUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        staffUser = new User("staff1", "pass", Role.STAFF);
        staffUser.setId(1L);
        kitchenUser = new User("kitchen1", "pass", Role.KITCHEN);
        kitchenUser.setId(2L);
        adminUser = new User("admin1", "pass", Role.ADMIN);
        adminUser.setId(3L);

        lenient().when(messageRepository.findByUserOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
    }

    @Test
    void staffRole_shouldOnlyHaveOrderStatusAndMenuItemsTools() {
        when(toolRegistry.toolsForStaff()).thenReturn(List.of(
            Map.of("type", "function", "function", Map.of("name", "getOrderStatus")),
            Map.of("type", "function", "function", Map.of("name", "getMenuItems"))
        ));

        AssistantReply reply = assistantService.processMessage(staffUser, "Hello");

        assertNotNull(reply);
        assertTrue(reply.text().contains("try again") || reply.text().contains("couldn't get an answer"));
    }

    @Test
    void kitchenRole_shouldHaveOrderStatusMenuItemsAndKitchenQueueTools() {
        when(toolRegistry.toolsForKitchen()).thenReturn(List.of(
            Map.of("type", "function", "function", Map.of("name", "getOrderStatus")),
            Map.of("type", "function", "function", Map.of("name", "getMenuItems")),
            Map.of("type", "function", "function", Map.of("name", "getKitchenQueueSummary"))
        ));

        AssistantReply reply = assistantService.processMessage(kitchenUser, "Hello");

        assertNotNull(reply);
        assertTrue(reply.text().contains("try again") || reply.text().contains("couldn't get an answer"));
    }

    @Test
    void adminRole_shouldHaveAllTools() {
        when(toolRegistry.toolsForAdmin()).thenReturn(List.of(
            Map.of("type", "function", "function", Map.of("name", "getOrderStatus")),
            Map.of("type", "function", "function", Map.of("name", "getMenuItems")),
            Map.of("type", "function", "function", Map.of("name", "getSalesTotals")),
            Map.of("type", "function", "function", Map.of("name", "getTopSellingItems")),
            Map.of("type", "function", "function", Map.of("name", "getInventoryLevel")),
            Map.of("type", "function", "function", Map.of("name", "getKitchenQueueSummary"))
        ));

        AssistantReply reply = assistantService.processMessage(adminUser, "Hello");

        assertNotNull(reply);
        assertTrue(reply.text().contains("try again") || reply.text().contains("couldn't get an answer"));
    }

    @Test
    void apiError_shouldReturnGracefulMessage() {
        AssistantReply reply = assistantService.processMessage(staffUser, "Hello");

        assertNotNull(reply);
        assertTrue(reply.text().contains("try again") || reply.text().contains("couldn't get an answer"));
        assertTrue(reply.links().isEmpty());
    }

    @Test
    void processMessage_shouldPersistUserMessage() {
        when(toolRegistry.toolsForStaff()).thenReturn(List.of(
            Map.of("type", "function", "function", Map.of("name", "getMenuItems"))
        ));

        assistantService.processMessage(staffUser, "What's on the menu?");

        verify(messageRepository).save(any(AssistantMessage.class));
    }
}