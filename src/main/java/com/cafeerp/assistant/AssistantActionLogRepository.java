package com.cafeerp.assistant;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.cafeerp.user.User;

public interface AssistantActionLogRepository extends JpaRepository<AssistantActionLog, Long> {

    /** Newest-first audit trail for one user. */
    List<AssistantActionLog> findByUserOrderByCreatedAtDescIdDesc(User user, Pageable pageable);

    /** Global audit trail for the admin action-log page. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "user")
    List<AssistantActionLog> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** Pending-confirmation actions of one user (chat thread render). */
    List<AssistantActionLog> findByUserAndStatusOrderByIdDesc(User user,
                                                              AssistantActionLog.Status status);

    /** Used by the admin hard-delete: the FK references cafe_user. */
    void deleteByUserId(Long userId);
}