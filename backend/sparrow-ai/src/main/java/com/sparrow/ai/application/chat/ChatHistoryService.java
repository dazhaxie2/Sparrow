package com.sparrow.ai.application.chat;

import com.sparrow.ai.application.chat.ChatHistoryViews.MessageItem;
import com.sparrow.ai.application.chat.ChatHistoryViews.SessionItem;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository.ChatMessageRow;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository.ChatSessionRow;
import com.sparrow.common.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天历史领域服务:会话列表/创建/删除/取消息,带用户归属校验。
 *
 * <p>范式参考 sparrow-industry-chain 的 ResearchConversationService。
 * 落库(存 user/assistant 消息)在 AiService 里做,本类只管会话元数据与历史读取。</p>
 */
@Service
public class ChatHistoryService {

    /** 会话标题最大长度(首问截断后)。 */
    private static final int TITLE_MAX = 20;

    private final ChatHistoryRepository repository;

    public ChatHistoryService(ChatHistoryRepository repository) {
        this.repository = repository;
    }

    /** 列出当前用户的全部会话(最近活跃在前)。 */
    public List<SessionItem> listSessions(long userId) {
        return repository.listSessions(userId).stream()
                .map(row -> new SessionItem(row.id(), row.title(),
                        repository.messageCount(row.id()), row.createdAt(), row.updatedAt()))
                .toList();
    }

    /**
     * 创建会话。title 由首问截断得来(去"围绕「xxx」回答:"前缀,取前 20 字)。
     *
     * @param userId  用户 id
     * @param question 首个问题(可为空,空则用默认标题)
     * @return 新会话 id
     */
    public long createSession(long userId, String question) {
        String title = buildTitle(question);
        return repository.createSession(userId, title);
    }

    /** 取会话的全部消息(历史回放)。归属不匹配抛 404。 */
    public List<MessageItem> getMessages(long userId, long sessionId) {
        requireOwned(userId, sessionId);
        return repository.messages(userId, sessionId).stream()
                .map(this::toItem)
                .toList();
    }

    /** 删除会话(级联删除消息)。归属不匹配抛 404。 */
    public void deleteSession(long userId, long sessionId) {
        requireOwned(userId, sessionId);
        repository.deleteSession(userId, sessionId);
    }

    /** 校验会话归属,不存在或不属于该用户则 404。供 AiService 落库前调用。 */
    public long requireOwned(long userId, long sessionId) {
        return repository.findSession(userId, sessionId)
                .orElseThrow(() -> new BizException(404, "会话不存在"))
                .id();
    }

    /** 首问转标题:去掉节点上下文前缀,截断到 20 字。 */
    private String buildTitle(String question) {
        if (question == null || question.isBlank()) {
            return "新对话";
        }
        String text = question.trim();
        // 去掉 useChat 前缀拼接的"围绕「xxx」回答:"
        text = text.replaceFirst("^围绕「[^」]*」回答[:：]\\s*", "");
        if (text.length() <= TITLE_MAX) {
            return text;
        }
        return text.substring(0, TITLE_MAX) + "…";
    }

    private MessageItem toItem(ChatMessageRow row) {
        return new MessageItem(row.id(), row.role(), row.content(), row.mode(), row.createdAt());
    }
}
