package com.cafeerp.assistant;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.cafeerp.user.User;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    List<AssistantMessage> findByUserOrderByCreatedAtAsc(User user);

    @Query("select distinct am.user from AssistantMessage am")
    List<User> findDistinctUsersWithMessages();
}