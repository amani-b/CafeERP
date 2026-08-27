package com.cafeerp.assistant;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auto-archives assistant conversations with no activity for
 * {@code assistant.chat.archive-after-days} (default 30).
 * <p>
 * Archival is SOFT: {@code archived_at} is stamped and the conversation
 * disappears from the default history list, but nothing is deleted — users
 * can recover archived threads via the history "Archived" filter. A hard
 * purge of long-dead archived conversations is a deliberate follow-up
 * decision (recommended: purge after 12 months archived).
 */
@Component
public class AssistantConversationArchivalJob {

    private static final Logger log = LoggerFactory.getLogger(AssistantConversationArchivalJob.class);

    private final AssistantConversationRepository conversationRepository;
    private final int archiveAfterDays;

    public AssistantConversationArchivalJob(
            AssistantConversationRepository conversationRepository,
            @Value("${assistant.chat.archive-after-days:30}") int archiveAfterDays) {
        this.conversationRepository = conversationRepository;
        this.archiveAfterDays = archiveAfterDays;
    }

    /**
     * Nightly at 03:00 server time. Public so tests (and the runbook's manual
     * maintenance) can trigger a run directly.
     *
     * @return number of conversations archived by this run
     */
    @Scheduled(cron = "${assistant.chat.archive-cron:0 0 3 * * *}")
    @Transactional
    public int archiveStaleConversations() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(archiveAfterDays);
        List<AssistantConversation> stale =
                conversationRepository.findByArchivedAtIsNullAndLastActivityAtBefore(cutoff);
        LocalDateTime now = LocalDateTime.now();
        for (AssistantConversation conversation : stale) {
            conversation.setArchivedAt(now);
            conversationRepository.save(conversation);
        }
        if (!stale.isEmpty()) {
            log.info("Auto-archived {} assistant conversation(s) idle for more than {} days",
                    stale.size(), archiveAfterDays);
        }
        return stale.size();
    }
}