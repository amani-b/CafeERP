package com.cafeerp.menu;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.cafeerp.category.CategoryService;
import com.cafeerp.inventory.InventoryService;

import jakarta.validation.Valid;

@Controller
@RequestMapping("/menu")
public class MenuController {

    private final MenuService menuService;
    private final CategoryService categoryService;
    private final InventoryService inventoryService;

    public MenuController(MenuService menuService, CategoryService categoryService, InventoryService inventoryService) {
        this.menuService = menuService;
        this.categoryService = categoryService;
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("menuItems", menuService.findAll());
        Set<Long> lowStockItemIds = inventoryService.findAll().stream()
                .filter(inv -> inv.isTrackInventory() && inv.getStockQuantity() <= inv.getLowStockThreshold())
                .map(inv -> inv.getMenuItem().getId())
                .collect(Collectors.toSet());
        model.addAttribute("lowStockItemIds", lowStockItemIds);
        return "menu/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("menuItem", new MenuItem());
        addCategories(model);
        return "menu/create";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute MenuItem menuItem, BindingResult bindingResult, Model model) {
        validateCategory(menuItem, bindingResult);

        if (bindingResult.hasErrors()) {
            addCategories(model);
            return "menu/create";
        }

        menuService.save(menuItem);
        return "redirect:/menu";
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("menuItem", menuService.findById(id));
        addCategories(model);
        return "menu/edit";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute MenuItem menuItem,
                         BindingResult bindingResult, Model model) {
        validateCategory(menuItem, bindingResult);

        if (bindingResult.hasErrors()) {
            menuItem.setId(id);
            addCategories(model);
            return "menu/edit";
        }

        menuItem.setId(id);
        menuService.save(menuItem);
        return "redirect:/menu";
    }

    private void addCategories(Model model) {
        model.addAttribute("categories", categoryService.findAll());
    }

    private void validateCategory(MenuItem menuItem, BindingResult bindingResult) {
        if (menuItem.getCategory() == null || menuItem.getCategory().getId() == null) {
            bindingResult.rejectValue("category", "category.required", "Category is required");
        }
    }
}
