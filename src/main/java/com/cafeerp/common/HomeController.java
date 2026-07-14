package com.cafeerp.common;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.cafeerp.inventory.InventoryService;

@Controller
public class HomeController {

    private final InventoryService inventoryService;

    public HomeController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("lowStockCount", inventoryService.countLowStock());
        return "home";
    }
}
