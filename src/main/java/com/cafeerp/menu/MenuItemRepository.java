package com.cafeerp.menu;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByAvailableTrue();
}
