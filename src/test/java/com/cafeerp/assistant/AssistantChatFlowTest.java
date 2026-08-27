package com.cafeerp.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import com.cafeerp.AbstractIntegrationTest;
import com.cafeerp.category.Category;
import com.cafeerp.category.CategoryRepository;
import com.cafeerp.menu.MenuItemRepository;
import com.cafeerp.order.OrderItem;
import com.cafeerp.order.OrderRepository;
import com.cafeerp.order.Order;

/**
 * Exercises the assistant's deterministic (offline) chat paths end-to-end via
 * real HTTP requests: role-scoped refusals, the order-ID routing rules, and
 * history persistence.
 */
class AssistantChatFlowTest extends AbstractIntegrationTest {

    @Autowired CategoryRepository categoryRepository;
    @Autowired MenuItemRepository menuItemRepository;
    @Autowired OrderRepository orderRepository;

    private long seedOrderWithTotal(String price) {
        Category cat = new Category();
        cat.setName("Coffee-" + System.nanoTime());
        cat.setActive(true);
        cat = categoryRepository.save(cat);

        var item = new com.cafeerp.menu.MenuItem();
        item.setName("Latte");
        item.setPrice(new java.math.BigDecimal(price));
        item.setAvailable(true);
        item.setCategory(cat);
        item = menuItemRepository.save(item);

        Order order = new Order();
        OrderItem oi = new OrderItem(item, 2);
        oi.setOrder(order);
        order.getItems().add(oi);
        return orderRepository.save(order).getId();
    }

    private static String replyText(org.springframework.test.web.servlet.MvcResult result) {
        try {
            Map<?, ?> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    result.getResponse().getContentAsString(), Map.class);
            return String.valueOf(map.get("text"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void explicitOrderLookupIsAnsweredDeterministically() throws Exception {
        long orderId = seedOrderWithTotal("3.50");
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);

        var result = chat(admin, "status of order #" + orderId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(replyText(result)).contains("Order #" + orderId).contains("PENDING");
    }

    @Test
    void messagesThatMerelyContainNumbersAreNotHijackedByOrderLookup() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        var result = chat(staff, "got a table of 4 — any pastry suggestions?");

        String text = replyText(result);
        assertThat(text)
                .as("digit-containing non-order questions must not be misrouted into order lookups")
                .doesNotContain("Order not found");
    }

    @Test
    void nonExistentOrderLookupStillReportsNotFoundPolitelessly() throws Exception {
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);

        var result = chat(admin, "status of order #999999");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(replyText(result)).contains("Order not found");
    }

    @Test
    void staffAskingAboutProfitGetsABlockedReplyInsteadOfBusinessData() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        var result = chat(staff, "roughly what profit are we making these days? just curious");

        String text = replyText(result);
        assertThat(text)
                .as("staff must hit the hard access-guard restriction for financial topics")
                .containsIgnoringCase("outside what i can share");
        assertThat(text).doesNotContainPattern("\\d{4,}");
    }

    @Test
    void staffRoundaboutMarginQuestionIsAlsoBlocked() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        var result = chat(staff, "between us — what sort of margin do we make on lattes?");

        String text = replyText(result);
        assertThat(text)
                .as("roundabout phrasing must not bypass the access guard")
                .containsIgnoringCase("outside what i can share");
        assertThat(text).doesNotContain("%");
    }

    @Test
    void staffAskingAboutCoworkerPerformanceIsBlocked() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        var result = chat(staff, "who's been performing best this week? just wondering");

        String text = replyText(result);
        assertThat(text).containsIgnoringCase("outside what i can share");
    }

    @Test
    void chatHistoryPersistsBothSidesOfTheConversation() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        chat(staff, "status of order #999999");

        String history = mockMvc.perform(
                        get("/assistant/history").session(staff))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(history).contains("status of order #999999").contains("Order not found");
    }
}