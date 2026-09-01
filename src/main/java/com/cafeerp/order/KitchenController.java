package com.cafeerp.order;

import java.time.ZonedDateTime;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.cafeerp.common.TimeDisplayService;

// Phase 3: admin-tier users need the ORDER_KITCHEN permission for the kitchen
// queue; the KITCHEN role keeps its existing access (URL rules still gate the
// role tiers), and SUPER_ADMIN always passes.
@Controller
@PreAuthorize("hasAnyRole('KITCHEN') or @permissions.has('ORDER_KITCHEN')")
@RequestMapping("/kitchen")
public class KitchenController {

    private final OrderService orderService;
    private final TimeDisplayService timeDisplayService;

    public KitchenController(OrderService orderService, TimeDisplayService timeDisplayService) {
        this.orderService = orderService;
        this.timeDisplayService = timeDisplayService;
    }

    @GetMapping
    public String queue(Model model) {
        model.addAttribute("orders", orderService.findActiveOrders());
        model.addAttribute("statuses", OrderStatus.values());
        // Server-anchored clock baseline: the header clock is computed from
        // this instant (not the device clock) and rendered in the business
        // timezone, so a misconfigured staff device cannot skew it.
        model.addAttribute("businessEpochMillis", ZonedDateTime.now().toInstant().toEpochMilli());
        model.addAttribute("businessZoneId", timeDisplayService.getBusinessZone().getId());
        return "kitchen/queue";
    }

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam OrderStatus status,
                               RedirectAttributes redirectAttributes) {
        try {
            orderService.updateStatus(id, status);
        } catch (IllegalArgumentException ex) {
            // handled by GlobalExceptionHandler -> 404
        }
        return "redirect:/kitchen";
    }
}