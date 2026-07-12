package com.sparrow.industrychain.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndustryChainRepositoryTest {

    @Test
    void runCheckpointReturnsNullWhenPersistedCheckpointIsNull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(startsWith("SELECT checkpoint_json FROM research_run"),
                org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                eq(9L), eq(4L), eq(202021L)))
                .thenReturn(Collections.singletonList(null));
        IndustryChainRepository repository = new IndustryChainRepository(jdbc);

        String checkpoint = repository.runCheckpoint(202021L, 4L, 9L);

        assertThat(checkpoint).isNull();
    }
}
