package com.cafeerp.category;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("categories", categoryService.findAll());
        return "categories/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("category", new Category());
        return "categories/create";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute Category category, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return "categories/create";
        }

        categoryService.save(category);
        return "redirect:/categories";
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("category", categoryService.findById(id));
        return "categories/edit";
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute Category category, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            category.setId(id);
            return "categories/edit";
        }

        category.setId(id);
        categoryService.save(category);
        return "redirect:/categories";
    }
}
