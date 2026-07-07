package com.sparrow.ai.application.chat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天历史对外视图 DTO。
 *
 * <p>把 Repository 的 Row(record,含内部字段)转成前端需要的精简形态,
 * 与 sparrow-industry-chain 的 ResearchCardViews 同一思路。</p>
 */
public final class ChatHistoryViews {

    private ChatHistoryViews() {
    }

    /** 会话列表项:精简,不含消息体。 */
    public record SessionItem(long id, String title, int messageCount,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 历史消息:回放时用。 */
    public record MessageItem(long id, String role, String content,
                              String mode, LocalDateTime createdAt) {
    }

    /** 创建会话的响应:返回新会话 id。 */
    public record CreateSessionResponse(long sessionId) {
    }

    /** 会话列表响应。 */
    public record SessionListResponse(List<SessionItem> sessions) {
    }
}
