package com.sparrow.common.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConfigRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fixedScenesDeclareTheirOnlyCompatibleKind() {
        assertThat(ModelScene.SPARROW_AI_EMBEDDING.expectedKind()).isEqualTo(ModelKind.EMBEDDING);
        assertThat(ModelScene.SPARROW_AI_CHAT.expectedKind()).isEqualTo(ModelKind.CHAT);
        assertThat(ModelScene.CHAIN_REPORT.expectedKind()).isEqualTo(ModelKind.CHAT);

        assertThatThrownBy(() -> ModelConfigRules.validate(
                "wrong", "https://provider.example/v1", "embed-1",
                ModelScene.SPARROW_AI_EMBEDDING, ModelKind.CHAT, 3000, 60, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("embedding");
    }

    @Test
    void unknownValuesNeverSilentlyBecomeDefaults() {
        assertThat(ModelScene.fromDbValue("unknown")).isNull();
        assertThat(ModelKind.fromDbValue("unknown")).isNull();
        assertThat(ModelScene.fromDbValue(null)).isNull();
        assertThat(ModelKind.fromDbValue(null)).isNull();
    }

    @Test
    void jsonContractUsesStableDatabaseValues() throws Exception {
        assertThat(mapper.writeValueAsString(ModelScene.CHAIN_PLANNING))
                .isEqualTo("\"chain_planning\"");
        assertThat(mapper.readValue("\"embedding\"", ModelKind.class))
                .isEqualTo(ModelKind.EMBEDDING);
    }
}
