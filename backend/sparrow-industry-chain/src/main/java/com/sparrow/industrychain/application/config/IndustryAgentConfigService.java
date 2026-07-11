package com.sparrow.industrychain.application.config;

import com.sparrow.common.ai.AiAgentProfile;
import com.sparrow.common.ai.AiAgentProfileRules;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import com.sparrow.common.security.AdminGuard;
import com.sparrow.industrychain.infrastructure.client.UserClient;
import com.sparrow.industrychain.infrastructure.persistence.AgentConfigRepository;
import com.sparrow.industrychain.infrastructure.persistence.AgentConfigRepository.AuditRow;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IndustryAgentConfigService {

    public static final String PLANNING_CHAT = "chain-planning-chat";
    public static final String RESEARCH_PLANNER = "chain-research-planner";
    public static final String INDUSTRY_RESEARCHER = "chain-industry-researcher";
    public static final String SEARCH_RESEARCHER = "chain-search-researcher";
    public static final String INSIGHT_RESEARCHER = "chain-insight-researcher";
    public static final String EVIDENCE_VALIDATOR = "chain-evidence-validator";
    public static final String FORUM_HOST = "chain-forum-host";
    public static final String GRAPH_EXTRACTOR = "chain-graph-extractor";
    public static final String REPORT_WRITER = "chain-report-writer";

    private static final Logger log = LoggerFactory.getLogger(IndustryAgentConfigService.class);
    private static final Map<String, AiAgentProfile> DEFAULTS = defaults();

    private final AgentConfigRepository repository;
    private final UserClient userClient;

    public IndustryAgentConfigService(AgentConfigRepository repository, UserClient userClient) {
        this.repository = repository;
        this.userClient = userClient;
    }

    @PostConstruct
    void seed() {
        DEFAULTS.values().forEach(repository::ensureDefault);
    }

    public AiAgentProfile runtime(String agentKey) {
        AiAgentProfile fallback = DEFAULTS.get(agentKey);
        if (fallback == null) {
            throw new BizException(404, "未知 Agent: " + agentKey);
        }
        try {
            return repository.find(agentKey).orElse(fallback);
        } catch (Exception error) {
            log.warn("Unable to load {} configuration; using safe default: {}",
                    agentKey, error.getClass().getSimpleName());
            return fallback;
        }
    }

    public AiAgentProfile requireEnabled(String agentKey) {
        AiAgentProfile profile = runtime(agentKey);
        if (!profile.enabled()) {
            throw new BizException(503, profile.displayName() + "已被管理员停用");
        }
        return profile;
    }

    public List<AiAgentProfile> list(long operatorId) {
        requireAdmin(operatorId);
        return repository.list();
    }

    public List<AuditRow> audits(long operatorId, int limit) {
        requireAdmin(operatorId);
        return repository.audits(Math.max(1, Math.min(limit, 200)));
    }

    @Transactional
    public AiAgentProfile save(long operatorId, SaveRequest request) {
        requireAdmin(operatorId);
        validate(request);
        AiAgentProfile existing = repository.find(request.agentKey())
                .orElseThrow(() -> new BizException(404, "Agent 配置不存在"));
        int rows = repository.update(request.agentKey(), request.systemPrompt().trim(), request.enabled(),
                request.maxContextMessages(), request.maxContextChars(), request.maxOutputChars(),
                request.maxSteps(), operatorId);
        if (rows != 1) {
            throw new BizException(409, "Agent 配置已变化，请刷新后重试");
        }
        repository.audit(request.agentKey(), operatorId,
                "更新提示词与运行参数（未记录提示词正文）");
        return repository.find(existing.agentKey()).orElse(existing);
    }

    private void requireAdmin(long operatorId) {
        try {
            ApiResponse<Map<String, Object>> response = userClient.profile(operatorId);
            AdminGuard.requireAdmin(AdminGuard.roleOf(response));
        } catch (BizException error) {
            throw error;
        } catch (Exception error) {
            throw new BizException(401, "无法确认管理员身份");
        }
    }

    private static void validate(SaveRequest request) {
        if (request == null || !DEFAULTS.containsKey(request.agentKey())) {
            throw new BizException(404, "Agent 配置不存在");
        }
        AiAgentProfileRules.validateRuntime(request.systemPrompt(), request.maxContextMessages(),
                request.maxContextChars(), request.maxOutputChars(), request.maxSteps());
    }

    private static Map<String, AiAgentProfile> defaults() {
        Map<String, AiAgentProfile> result = new LinkedHashMap<>();
        add(result, PLANNING_CHAT, "调研规划对话 Agent", "产业链卡片右侧规划对话。",
                "你是产业链深度调研工作台的规划 Agent。通过对话帮助用户明确产品、企业、地域、时间范围、上下游边界和重点问题；不假装已经联网，不编造供应关系，信息不足时一次最多追问三个关键问题。",
                12, 8000, 60000, 3);
        add(result, RESEARCH_PLANNER, "深度调研规划 Agent", "拆解深度调研计划与检索方向。",
                "你是产业链研究规划 Agent。制定可执行、可核验的调研计划，覆盖上下游、核心企业、竞争格局、政策、供应风险和技术趋势；只规划，不填充未经来源核验的事实。",
                16, 12000, 60000, 8);
        add(result, INDUSTRY_RESEARCHER, "行业结构 Agent", "研究产业链结构、企业与竞争格局。",
                "你是产业链行业结构调研 Agent，专注上下游环节、核心企业与竞争格局。只依据来源给出结论，每条事实标注来源，证据不足写待核验。",
                8, 8000, 30000, 2);
        add(result, SEARCH_RESEARCHER, "权威检索 Agent", "检索权威报告、份额、政策与趋势。",
                "你是产业链权威检索 Agent，侧重权威报告、市场份额、政策与技术趋势的精准检索。只依据来源，不得把推测写成事实。",
                8, 8000, 30000, 2);
        add(result, INSIGHT_RESEARCHER, "纵深洞察 Agent", "研究风险、依赖与潜在断点。",
                "你是产业链纵深洞察 Agent，侧重供应风险、依赖关系与潜在断点。区分事实、推断和待核验项，并为事实标注来源。",
                8, 8000, 30000, 2);
        add(result, EVIDENCE_VALIDATOR, "证据核验 Agent", "交叉核验多 Agent 结论。",
                "你是证据核验 Agent。只能使用提供的来源整理可直接支持的产业链事实；忽略广告与无法核实的内容，每条事实必须标注来源编号，证据不足明确写待核验。",
                0, 16000, 60000, 5);
        add(result, FORUM_HOST, "论坛主持人 Agent", "整合多 Agent 发言并引导下一轮。",
                "你是产业链调研论坛主持人。整合时间线、观点、分歧和下一轮问题，不得编造发言中不存在的事实，输出简洁中文总结。",
                5, 6000, 10000, 3);
        add(result, GRAPH_EXTRACTOR, "关系图 Agent", "从核验证据提取产业链节点与关系。",
                "你是产业链关系抽取 Agent。只根据核验证据生成严格 JSON 关系图；每条关系必须带来源，不得输出 Markdown 围栏或无来源事实。",
                0, 20000, 100000, 5);
        add(result, REPORT_WRITER, "深度报告 Agent", "根据证据、图谱与论坛结论生成报告。",
                "你是产业链深度报告 Agent。仅基于核验证据、关系图和多 Agent 结论生成结构清晰的中文报告；事实必须可追溯到来源，不得补造数据。",
                0, 30000, 100000, 8);
        return Map.copyOf(result);
    }

    private static void add(Map<String, AiAgentProfile> target, String key, String name,
                            String description, String prompt, int contextMessages,
                            int contextChars, int outputChars, int maxSteps) {
        target.put(key, new AiAgentProfile("sparrow-industry-chain", key, name, description,
                prompt, true, contextMessages, contextChars, outputChars, maxSteps, null, null));
    }

    public record SaveRequest(String agentKey, String systemPrompt, boolean enabled,
                              int maxContextMessages, int maxContextChars,
                              int maxOutputChars, int maxSteps) {
    }
}
