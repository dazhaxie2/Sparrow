package com.sparrow.ai.infrastructure.agent;

import com.sparrow.ai.infrastructure.client.GraphClient;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeBrief;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeDetail;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 科技树图谱查询工具。
 * 供 TechTreeAgent 调用,用于查询技术节点详情和前置依赖链。
 */
@Component
public class GraphQueryTool {

    private final GraphClient graphClient;

    /**
     * 构造函数。
     *
     * @param graphClient 图谱服务客户端
     */
    public GraphQueryTool(GraphClient graphClient) {
        this.graphClient = graphClient;
    }

    @Tool("根据技术名称或编码查询科技节点的详细信息,包括描述、时代、前置技术和解锁技术")
    public String queryNode(
            @P("技术节点的名称,例如'蒸汽机'、'计算机'、'火'等") String techName) {
        var treeResp = graphClient.tree();
        if (treeResp == null || treeResp.data() == null) {
            return "图谱服务暂不可用";
        }
        var nodes = treeResp.data().nodes();
        NodeBrief matched = null;
        for (NodeBrief n : nodes) {
            if (n.name().contains(techName) || n.code().equalsIgnoreCase(techName)) {
                matched = n;
                break;
            }
        }
        if (matched == null) {
            return "未找到技术节点: " + techName;
        }
        var detailResp = graphClient.nodeDetail(matched.id(), null);
        if (detailResp == null || detailResp.data() == null) {
            return "节点: " + matched.name() + " (" + matched.era() + " " + matched.yearLabel() + ")\n"
                    + matched.summary();
        }
        NodeDetail d = detailResp.data();
        StringBuilder sb = new StringBuilder();
        sb.append("节点: ").append(d.name()).append(" (").append(d.era())
                .append(" ").append(d.yearLabel()).append(")\n");
        sb.append("摘要: ").append(d.summary()).append("\n");
        if (d.detail() != null && !d.detail().isBlank()) {
            sb.append("详情: ").append(d.detail()).append("\n");
        }
        if (!d.prerequisites().isEmpty()) {
            sb.append("直接前置技术: ")
                    .append(d.prerequisites().stream().map(NodeBrief::name).collect(Collectors.joining("、")))
                    .append("\n");
        }
        if (!d.unlocks().isEmpty()) {
            sb.append("直接解锁技术: ")
                    .append(d.unlocks().stream().map(NodeBrief::name).collect(Collectors.joining("、")))
                    .append("\n");
        }
        return sb.toString();
    }

    @Tool("查询某个技术的完整前置依赖链,返回从最基础技术到该技术的完整路径")
    public String queryPrerequisiteChain(
            @P("技术节点的名称,例如'蒸汽机'、'互联网'等") String techName) {
        var treeResp = graphClient.tree();
        if (treeResp == null || treeResp.data() == null) {
            return "图谱服务暂不可用";
        }
        NodeBrief matched = null;
        for (NodeBrief n : treeResp.data().nodes()) {
            if (n.name().contains(techName) || n.code().equalsIgnoreCase(techName)) {
                matched = n;
                break;
            }
        }
        if (matched == null) {
            return "未找到技术节点: " + techName;
        }
        var chainResp = graphClient.prerequisites(matched.id());
        if (chainResp == null || chainResp.data() == null || chainResp.data().isEmpty()) {
            return matched.name() + "没有前置技术(它是基础技术)";
        }
        List<NodeBrief> chain = chainResp.data();
        StringBuilder sb = new StringBuilder();
        sb.append(matched.name()).append("的完整前置技术链(共").append(chain.size()).append("项):\n");
        for (NodeBrief n : chain) {
            sb.append("· ").append(n.name()).append(" (").append(n.era())
                    .append(" ").append(n.yearLabel()).append(")\n");
        }
        return sb.toString();
    }
}
