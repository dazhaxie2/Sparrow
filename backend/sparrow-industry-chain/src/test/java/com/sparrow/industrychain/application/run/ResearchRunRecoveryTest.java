package com.sparrow.industrychain.application.run;

import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchRunRecoveryTest {

    @Test
    void marksRunsFromPreviousProcessAsRecoverableFailures() {
        IndustryChainRepository repository = mock(IndustryChainRepository.class);
        when(repository.failInterruptedRuns(ResearchRunRecovery.INTERRUPTION_MESSAGE)).thenReturn(2);
        ResearchRunRecovery recovery = new ResearchRunRecovery(repository);

        recovery.recoverInterruptedRuns();

        verify(repository).failInterruptedRuns(ResearchRunRecovery.INTERRUPTION_MESSAGE);
    }
}
