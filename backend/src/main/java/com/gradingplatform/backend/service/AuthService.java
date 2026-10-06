package com.gradingplatform.backend.service;

import com.gradingplatform.backend.dto.SignupRequest;
import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** Creates a user. The role is always {@link Role#STUDENT}; only an admin can promote later. */
    @Transactional
    public User signup(SignupRequest request) {
        String passwordHash = passwordEncoder.encode(request.password());
        return users.save(new User(request.email(), passwordHash, request.fullName(), Role.STUDENT));
    }
}
