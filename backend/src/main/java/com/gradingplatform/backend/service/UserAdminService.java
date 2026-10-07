package com.gradingplatform.backend.service;

import com.gradingplatform.backend.entity.Role;
import com.gradingplatform.backend.entity.User;
import com.gradingplatform.backend.repository.UserRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What an admin does to other accounts. Who may call it is decided in the controller. */
@Service
public class UserAdminService {

    private final UserRepository users;

    public UserAdminService(UserRepository users) {
        this.users = users;
    }

    /**
     * Gives a user a role. Setting the role the user already has changes nothing and succeeds.
     * An admin may demote themselves, but never the last admin.
     *
     * <p>The admin rows are locked before anything is decided, so two role changes cannot run side
     * by side: without it, two admins demoting each other at the same moment would each still see
     * the other as an admin and leave none. The second change waits for the first to commit, then
     * counts again.
     *
     * @throws UserNotFoundException if no user has this id
     * @throws LastAdminException if the user is the only admin and the new role is not {@code ADMIN}
     */
    @Transactional
    public User changeRole(long userId, Role newRole) {
        List<User> admins = users.lockAllByRole(Role.ADMIN);
        User user = users.findById(userId).orElseThrow(UserNotFoundException::new);
        if (user.getRole() == Role.ADMIN && newRole != Role.ADMIN && admins.size() <= 1) {
            throw new LastAdminException();
        }
        user.setRole(newRole);
        return user;
    }

    /** One page of all users, in the order the caller's {@code pageable} asks for (the controller fixes it by id). */
    @Transactional(readOnly = true)
    public Page<User> listUsers(Pageable pageable) {
        return users.findAll(pageable);
    }
}
