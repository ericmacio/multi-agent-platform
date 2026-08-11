package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.application.user.ListUsersUseCase.ListUsersQuery;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListUsersServiceTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private ListUsersService service;

    @Test
    void forwards_a_null_cursor_to_the_repository_for_the_first_page() {
        Page<User> repoPage = new Page<>(List.<User>of(), null, 20);
        when(userRepository.listAll(null, 20)).thenReturn(repoPage);

        Page<User> result = service.list(new ListUsersQuery(null, PageSize.fromQueryParam(null)));

        assertThat(result).isSameAs(repoPage);
    }

    @Test
    void forwards_the_cursor_and_page_size_verbatim() {
        Cursor cursor = new Cursor(OffsetDateTime.now(ZoneOffset.UTC), "id-1");
        Page<User> repoPage = new Page<>(List.<User>of(), null, 50);
        when(userRepository.listAll(cursor, 50)).thenReturn(repoPage);

        Page<User> result = service.list(new ListUsersQuery(cursor, new PageSize(50)));

        assertThat(result).isSameAs(repoPage);
    }
}
