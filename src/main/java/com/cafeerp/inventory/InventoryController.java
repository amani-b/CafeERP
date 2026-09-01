package com.cafeerp.inventory;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

// Phase 3: requires the INVENTORY permission for admin-tier users; SUPER_ADMIN
// always passes. STAFF/KITCHEN are already excluded by the URL rules.
@Controller
@PreAuthorize("@permissions.has('INVENTORY')")
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("inventoryList", inventoryService.findAll());
        return "inventory/list";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam boolean trackInventory,
                         @RequestParam int stockQuantity,
                         @RequestParam int lowStockThreshold) {
        inventoryService.update(id, trackInventory, stockQuantity, lowStockThreshold);
        return "redirect:/inventory";
    }
}