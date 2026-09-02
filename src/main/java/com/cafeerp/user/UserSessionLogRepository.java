package com.cafeerp.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionLogRepository extends JpaRepository<UserSessionLog, Long> {

    /** Newest-first event history for one user (by username snapshot). */
    List<UserSessionLog> findByUsernameIgnoreCaseOrderByOccurredAtDescIdDesc(String username, org.springframework.data.domain.Pageable pageable);

    /** Used by the admin hard-delete: the FK references cafe_user. */
    void deleteByUserId(Long userId);
}