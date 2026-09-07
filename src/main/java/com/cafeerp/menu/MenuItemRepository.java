package com.cafeerp.menu;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByAvailableTrue();

    /**
     * Phase 9: indexed single-row lookup by name (case-insensitive) for the
     * assistant's tools. Replaces the previous load-everything-and-scan
     * pattern (menuService.findAll() + linear Java filter) on every tool
     * call.
     */
    Optional<MenuItem> findFirstByNameIgnoreCase(String name);
}
