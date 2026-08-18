package com.cafeerp.user;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void findById_whenFound_shouldReturnUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole(Role.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        User result = userService.findById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUsername()).isEqualTo("admin");
        assertThat(result.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void findById_whenNotFound_shouldThrow() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User not found");
    }

    @Test
    void usernameExists_whenPresent_shouldReturnTrue() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(new User()));

        assertThat(userService.usernameExists("admin")).isTrue();
    }

    @Test
    void usernameExists_whenAbsent_shouldReturnFalse() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThat(userService.usernameExists("nobody")).isFalse();
    }

    @Test
    void createUser_shouldEncodePasswordAndPersist() {
        User toCreate = new User();
        toCreate.setUsername("barista");
        toCreate.setPassword("plain-password");
        toCreate.setRole(Role.STAFF);

        User saved = new User();
        saved.setId(1L);
        saved.setUsername("barista");
        saved.setPassword("encoded-password");
        saved.setRole(Role.STAFF);

        when(passwordEncoder.encode("plain-password")).thenReturn("encoded-password");
        when(userRepository.save(any())).thenReturn(saved);

        User result = userService.createUser(toCreate);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUsername()).isEqualTo("barista");
        assertThat(result.getPassword()).isEqualTo("encoded-password");
        assertThat(result.getRole()).isEqualTo(Role.STAFF);
        verify(passwordEncoder).encode("plain-password");
        verify(userRepository).save(any());
    }

    @Test
    void updateUser_shouldUpdateFieldsAndPreservePassword() {
        User existing = new User();
        existing.setId(1L);
        existing.setUsername("barista");
        existing.setPassword("existing-hash");
        existing.setRole(Role.STAFF);
        existing.setMustChangePassword(false);

        User toUpdate = new User();
        toUpdate.setId(1L);
        toUpdate.setUsername("barista");
        toUpdate.setPassword(null); // password not submitted on edit
        toUpdate.setRole(Role.KITCHEN);
        toUpdate.setMustChangePassword(true);

        User saved = new User();
        saved.setId(1L);
        saved.setUsername("barista");
        saved.setPassword("existing-hash");
        saved.setRole(Role.KITCHEN);
        saved.setMustChangePassword(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.save(any())).thenReturn(saved);

        User result = userService.updateUser(toUpdate);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUsername()).isEqualTo("barista");
        assertThat(result.getPassword()).isEqualTo("existing-hash");
        assertThat(result.getRole()).isEqualTo(Role.KITCHEN);
        assertThat(result.isMustChangePassword()).isTrue();
        verify(userRepository).save(any());
    }
}