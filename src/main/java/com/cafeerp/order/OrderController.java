package com.cafeerp.order;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.cafeerp.menu.MenuService;

@Controller
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final MenuService menuService;

    public OrderController(OrderService orderService, MenuService menuService) {
        this.orderService = orderService;
        this.menuService = menuService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("orders", orderService.findAll());
        return "orders/list";
    }

    @GetMapping("/{id}")
    public String receipt(@PathVariable Long id, Model model) {
        model.addAttribute("order", orderService.findById(id));
        return "orders/receipt";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("menuItems", menuService.findAvailable());
        return "orders/create";
    }

    @PostMapping
    public String create(@RequestParam Map<String, String> formValues, RedirectAttributes redirectAttributes) {
        try {
            orderService.createOrder(parseQuantities(formValues));
            return "redirect:/orders";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/orders/new";
        }
    }

    private Map<Long, Integer> parseQuantities(Map<String, String> formValues) {
        Map<Long, Integer> quantities = new HashMap<>();

        formValues.forEach((name, value) -> {
            if (!name.startsWith("quantities[")) {
                return;
            }

            try {
                Long menuItemId = Long.valueOf(name.substring(11, name.length() - 1));
                int quantity = value == null || value.isBlank() ? 0 : Integer.parseInt(value);
                quantities.put(menuItemId, quantity);
            } catch (NumberFormatException | IndexOutOfBoundsException ex) {
                // Ignore malformed quantity fields instead of failing the whole order.
            }
        });

        return quantities;
    }
}
