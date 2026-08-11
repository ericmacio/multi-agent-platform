package com.cognizant.emk.multiagent.infrastructure.persistence.mapper;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa;

/**
 * Translates between the {@link User} domain aggregate and the {@link UserJpa} entity.
 *
 * <p>Pure Java — no Spring stereotypes, no JPA imports beyond the entity itself. The
 * adapter ({@code UserRepositoryAdapter}) is the single entry point that uses this mapper.
 */
public final class UserMapper {

    private UserMapper() {}

    public static User toDomain(UserJpa jpa) {
        return new User(
                new UserId(jpa.getId()),
                new Email(jpa.getEmail()),
                jpa.getPasswordHash(),
                Role.valueOf(jpa.getRole()),
                jpa.isDisabled(),
                jpa.isMustChangePassword(),
                jpa.getCreatedAt(),
                jpa.getUpdatedAt());
    }

    public static UserJpa toJpa(User user) {
        return new UserJpa(
                user.id().value(),
                user.email().value(),
                user.passwordHash(),
                user.role().name(),
                user.disabled(),
                user.mustChangePassword(),
                user.createdAt(),
                user.updatedAt());
    }
}
