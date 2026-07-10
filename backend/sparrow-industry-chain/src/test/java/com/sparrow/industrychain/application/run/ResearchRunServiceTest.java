package com.sparrow.industrychain.application.run;

import com.sparrow.industrychain.infrastructure.event.IndustryChainEventHub;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.CardRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.RunRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchRunServiceTest {

    @Test
    void resumeReusesFailedRunAndDoesNotConsumeQuota() {
        IndustryChainRepository repository = mock(IndustryChainRepository.class);
        ResearchRunner runner = mock(ResearchRunner.class);
        ResearchQuotaService quota = mock(ResearchQuotaService.class);
        IndustryChainEventHub events = mock(IndustryChainEventHub.class);
        ResearchRunService service = new ResearchRunService(repository, runner, quota, events);
        LocalDateTime started = LocalDateTime.now().minusMinutes(10);
        when(repository.findCard(7L, 11L)).thenReturn(Optional.of(new CardRow(
                11L, 7L, "机器人", null, "FAILED", "verifying", 58,
                0, 0, null, null, null, "timeout", started, started)));
        when(repository.resumeLastFailed(7L, 11L)).thenReturn(new RunRow(
                21L, 11L, "RUNNING", "verifying", 58, null, started, null));

        var result = service.resume(7L, 11L);

        assertThat(result.runId()).isEqualTo(21L);
        assertThat(result.currentStage()).isEqualTo("verifying");
        assertThat(result.progress()).isEqualTo(58);
        verify(runner).run(7L, 11L, 21L, true);
        verify(quota, never()).consume(7L);
    }
}
