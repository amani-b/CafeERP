package com.cafeerp.assistant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;

@DataJpaTest
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class AssistantMessageRepositoryTest {

    @Autowired
    private AssistantMessageRepository assistantMessageRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void saveAndRetrieveMessagesInChronologicalOrder() {
        User user = userRepository.save(new User("alice", "pass", Role.STAFF));

        AssistantMessage msg1 = new AssistantMessage(user, AssistantMessageRole.USER, "Hello");
        AssistantMessage msg2 = new AssistantMessage(user, AssistantMessageRole.ASSISTANT, "Hi there");
        AssistantMessage msg3 = new AssistantMessage(user, AssistantMessageRole.USER, "How are you?");

        assistantMessageRepository.save(msg1);
        assistantMessageRepository.save(msg2);
        assistantMessageRepository.save(msg3);

        List<AssistantMessage> messages = assistantMessageRepository.findByUserOrderByCreatedAtAsc(user);

        assertEquals(3, messages.size());
        assertEquals(AssistantMessageRole.USER, messages.get(0).getRole());
        assertEquals("Hello", messages.get(0).getContent());
        assertEquals(AssistantMessageRole.ASSISTANT, messages.get(1).getRole());
        assertEquals("Hi there", messages.get(1).getContent());
        assertEquals(AssistantMessageRole.USER, messages.get(2).getRole());
        assertEquals("How are you?", messages.get(2).getContent());
        assertTrue(messages.get(0).getCreatedAt().isBefore(messages.get(1).getCreatedAt())
                || messages.get(0).getCreatedAt().isEqual(messages.get(1).getCreatedAt()));
        assertTrue(messages.get(1).getCreatedAt().isBefore(messages.get(2).getCreatedAt())
                || messages.get(1).getCreatedAt().isEqual(messages.get(2).getCreatedAt()));
    }

    @Test
    void findDistinctUsersWithMessages() {
        User alice = userRepository.save(new User("alice", "pass", Role.STAFF));
        User bob = userRepository.save(new User("bob", "pass", Role.STAFF));
        User charlie = userRepository.save(new User("charlie", "pass", Role.KITCHEN));

        // alice and bob have messages; charlie does not
        assistantMessageRepository.save(new AssistantMessage(alice, AssistantMessageRole.USER, "Hi"));
        assistantMessageRepository.save(new AssistantMessage(bob, AssistantMessageRole.USER, "Hello"));

        List<User> usersWithMessages = assistantMessageRepository.findDistinctUsersWithMessages();

        assertEquals(2, usersWithMessages.size());
        assertTrue(usersWithMessages.stream().anyMatch(u -> u.getUsername().equals("alice")));
        assertTrue(usersWithMessages.stream().anyMatch(u -> u.getUsername().equals("bob")));
    }
}