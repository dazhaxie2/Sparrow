package com.sparrow.industrychain.application.source;

import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.SourceInput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SourceRefAllocator {

    public List<SourceInput> renumber(List<SourceInput> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        List<SourceInput> numbered = new ArrayList<>(sources.size());
        int index = 1;
        for (SourceInput source : sources) {
            numbered.add(new SourceInput(next(index++), source.title(), source.url(),
                    source.publisher(), source.snippet()));
        }
        return numbered;
    }

    public String nextAfter(int existingCount) {
        return next(existingCount + 1);
    }

    private String next(int index) {
        return "S" + index;
    }
}
