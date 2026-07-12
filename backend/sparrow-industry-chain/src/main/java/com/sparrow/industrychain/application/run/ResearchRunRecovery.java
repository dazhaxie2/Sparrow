package com.sparrow.industrychain.application.run;

import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 将进程重启时失去执行线程的任务转成可恢复失败态。 */
@Component
public class ResearchRunRecovery {

    static final String INTERRUPTION_MESSAGE = "服务重启导致调研中断，已保留检查点，可从中断点继续。";

    private static final Logger log = LoggerFactory.getLogger(ResearchRunRecovery.class);
    private final IndustryChainRepository repository;

    public ResearchRunRecovery(IndustryChainRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void recoverInterruptedRuns() {
        int recovered = repository.failInterruptedRuns(INTERRUPTION_MESSAGE);
        if (recovered > 0) {
            log.warn("已收口服务重启遗留的产业链调研任务: count={}", recovered);
        }
    }
}
