package com.sparrow.ai.infrastructure.agent;

import com.sparrow.ai.infrastructure.client.UserClient;
import com.sparrow.common.api.ApiResponse;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UserProgressTool {

    private final UserClient userClient;

    public UserProgressTool(UserClient userClient) {
        this.userClient = userClient;
    }

    @Tool("查询用户的会员状态、AI 额度和基本信息")
    public String userProgress(
            @P("用户ID") long userId) {
        try {
            ApiResponse<Map<String, Object>> resp = userClient.membership(userId);
            if (resp == null || resp.data() == null) {
                return "无法获取用户信息";
            }
            Map<String, Object> data = resp.data();
            boolean member = Boolean.TRUE.equals(data.get("member"));
            return "用户ID: " + userId + ", 会员状态: " + (member ? "会员" : "免费用户");
        } catch (Exception e) {
            return "查询用户信息失败: " + e.getMessage();
        }
    }
}
