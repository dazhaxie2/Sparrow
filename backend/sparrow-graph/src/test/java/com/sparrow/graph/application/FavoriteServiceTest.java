package com.sparrow.graph.application;

import com.sparrow.common.exception.BizException;
import com.sparrow.graph.domain.model.FavoriteFolder;
import com.sparrow.graph.infrastructure.persistence.FavoriteFolderMapper;
import com.sparrow.graph.infrastructure.persistence.FavoriteItemMapper;
import com.sparrow.graph.infrastructure.persistence.TechNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FavoriteService 纯 mock 单测 —— 仅覆盖不构造 Wrapper 的校验/分支路径。
 *
 * <p>根本限制:MyBatis-Plus 3.5.9 的 {@code LambdaQueryWrapper.eq(Entity::getter, value)}
 * 在构造时即触发 lambda cache 解析(需运行时实体元数据),纯 mock 环境无 TableInfo 初始化,
 * 任何 selectCount/selectOne/selectList/update/delete with Wrapper 的方法都会抛
 * {@code MybatisPlusException: can not find lambda cache for this entity}。
 * 故涉及 Wrapper 的逻辑(rename 重名校验、createFolder 成功、deleteFolder 成功、favoriteNode、
 * moveNode、folderIdForNode、unfavoriteNode 等)需 DB 集成测试(H2/MySQL + @MybatisPlusTest)补充。</p>
 *
 * <p>本测试覆盖的 5 条路径均在构造 Wrapper 前完成校验或抛出:名称校验(空/超长→400)、
 * 默认夹保护(rename/delete 默认夹→400)、收藏夹不存在(selectById→404)。</p>
 */
class FavoriteServiceTest {

    private FavoriteFolderMapper folderMapper;
    private FavoriteItemMapper itemMapper;
    private TechNodeMapper nodeMapper;
    private FavoriteService service;

    @BeforeEach
    void setUp() {
        folderMapper = mock(FavoriteFolderMapper.class);
        itemMapper = mock(FavoriteItemMapper.class);
        nodeMapper = mock(TechNodeMapper.class);
        service = new FavoriteService(folderMapper, itemMapper, nodeMapper);
    }

    // ===== createFolder:名称校验(在 selectCount 前,不构造 Wrapper)=====

    @Test
    void createFolderRejectsEmptyName() {
        BizException e = assertThrows(BizException.class, () -> service.createFolder(42L, "  "));
        assertEquals(400, e.getCode());
        verify(folderMapper, never()).selectCount(any());
    }

    @Test
    void createFolderRejectsNameTooLong() {
        String tooLong = "a".repeat(65);
        BizException e = assertThrows(BizException.class, () -> service.createFolder(42L, tooLong));
        assertEquals(400, e.getCode());
    }

    // ===== renameFolder:默认夹保护 / 不存在(均不构造 Wrapper)=====

    @Test
    void renameFolderRejectsDefaultFolder() {
        when(folderMapper.selectById(1L)).thenReturn(folder(42L, 1L, "我的收藏"));

        BizException e = assertThrows(BizException.class, () -> service.renameFolder(42L, 1L, "新名"));
        assertEquals(400, e.getCode());
    }

    @Test
    void renameFolderThrowsWhenFolderMissing() {
        when(folderMapper.selectById(99L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.renameFolder(42L, 99L, "新名"));
    }

    // ===== deleteFolder:默认夹保护(getFolder 后立即抛,不进入 getDefaultFolder 的 Wrapper)=====

    @Test
    void deleteFolderRejectsDefaultFolder() {
        when(folderMapper.selectById(1L)).thenReturn(folder(42L, 1L, "我的收藏"));

        BizException e = assertThrows(BizException.class, () -> service.deleteFolder(42L, 1L));
        assertEquals(400, e.getCode());
    }

    /** 构造一个已设 userId/id/name 的 FavoriteFolder。 */
    private FavoriteFolder folder(long userId, long id, String name) {
        FavoriteFolder f = new FavoriteFolder();
        f.setUserId(userId);
        f.setId(id);
        f.setName(name);
        return f;
    }
}
