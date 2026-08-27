package com.cafeerp.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Active (not soft-deleted) account lookup — used for authentication. */
    Optional<User> findByUsernameAndDeletedAtIsNull(String username);

    Optional<User> findByUsername(String username);

    /** Default admin list: active users only. */
    List<User> findAllByDeletedAtIsNullOrderByIdAsc();

    /** "Show inactive" admin list: soft-deleted users only. */
    List<User> findAllByDeletedAtIsNotNullOrderByIdAsc();
}