package com.sparrow.graph.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sparrow.common.exception.BizException;
import com.sparrow.graph.domain.model.FavoriteFolder;
import com.sparrow.graph.domain.model.FavoriteItem;
import com.sparrow.graph.domain.model.TechNode;
import com.sparrow.graph.infrastructure.persistence.FavoriteFolderMapper;
import com.sparrow.graph.infrastructure.persistence.FavoriteItemMapper;
import com.sparrow.graph.infrastructure.persistence.TechNodeMapper;
import com.sparrow.graph.interfaces.dto.FavoriteDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 一句话：收藏夹与节点收藏的增删改查及移动，按 user_id 隔离。 */
@Service
public class FavoriteService {

    private static final String DEFAULT_FOLDER_NAME = "我的收藏";

    private final FavoriteFolderMapper folderMapper;
    private final FavoriteItemMapper itemMapper;
    private final TechNodeMapper nodeMapper;

    public FavoriteService(FavoriteFolderMapper folderMapper, FavoriteItemMapper itemMapper, TechNodeMapper nodeMapper) {
        this.folderMapper = folderMapper;
        this.itemMapper = itemMapper;
        this.nodeMapper = nodeMapper;
    }

    /** 一句话：查询用户全部收藏夹，不存在默认收藏夹时自动创建。 */
    @Transactional(rollbackFor = Exception.class)
    public List<FavoriteDtos.FolderBrief> listFolders(Long userId) {
        ensureDefaultFolder(userId);
        List<FavoriteFolder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<FavoriteFolder>().eq(FavoriteFolder::getUserId, userId));
        folders.sort(Comparator.comparingInt(FavoriteFolder::getSortOrder)
                .thenComparing(FavoriteFolder::getCreatedAt));
        return folders.stream()
                .map(f -> new FavoriteDtos.FolderBrief(f.getId(), f.getName(), f.getSortOrder()))
                .toList();
    }

    /** 一句话：创建收藏夹，名称在同一用户下唯一。 */
    @Transactional(rollbackFor = Exception.class)
    public FavoriteDtos.FolderBrief createFolder(Long userId, String name) {
        String trimmed = trimName(name);
        if (folderMapper.selectCount(
                new LambdaQueryWrapper<FavoriteFolder>()
                        .eq(FavoriteFolder::getUserId, userId)
                        .eq(FavoriteFolder::getName, trimmed)) > 0) {
            throw new BizException(409, "收藏夹名称已存在");
        }
        FavoriteFolder folder = new FavoriteFolder();
        folder.setUserId(userId);
        folder.setName(trimmed);
        folder.setSortOrder(0);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        folderMapper.insert(folder);
        return new FavoriteDtos.FolderBrief(folder.getId(), folder.getName(), folder.getSortOrder());
    }

    /** 一句话：重命名收藏夹，默认收藏夹不可改名。 */
    @Transactional(rollbackFor = Exception.class)
    public FavoriteDtos.FolderBrief renameFolder(Long userId, Long folderId, String name) {
        FavoriteFolder folder = getFolder(userId, folderId);
        if (DEFAULT_FOLDER_NAME.equals(folder.getName())) {
            throw new BizException(400, "默认收藏夹不可重命名");
        }
        String trimmed = trimName(name);
        if (!trimmed.equals(folder.getName()) && folderMapper.selectCount(
                new LambdaQueryWrapper<FavoriteFolder>()
                        .eq(FavoriteFolder::getUserId, userId)
                        .eq(FavoriteFolder::getName, trimmed)) > 0) {
            throw new BizException(409, "收藏夹名称已存在");
        }
        folder.setName(trimmed);
        folder.setUpdatedAt(LocalDateTime.now());
        folderMapper.updateById(folder);
        return new FavoriteDtos.FolderBrief(folder.getId(), folder.getName(), folder.getSortOrder());
    }

    /** 一句话：删除收藏夹，默认收藏夹不可删；删除时夹内节点回到默认收藏夹。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFolder(Long userId, Long folderId) {
        FavoriteFolder folder = getFolder(userId, folderId);
        if (DEFAULT_FOLDER_NAME.equals(folder.getName())) {
            throw new BizException(400, "默认收藏夹不可删除");
        }
        FavoriteFolder defaultFolder = getDefaultFolder(userId);
        itemMapper.update(null, new LambdaUpdateWrapper<FavoriteItem>()
                .eq(FavoriteItem::getUserId, userId)
                .eq(FavoriteItem::getFolderId, folderId)
                .set(FavoriteItem::getFolderId, defaultFolder.getId())
                .set(FavoriteItem::getUpdatedAt, LocalDateTime.now()));
        folderMapper.deleteById(folderId);
    }

    /** 一句话：收藏节点到指定收藏夹；若已存在则移动到目标收藏夹。 */
    @Transactional(rollbackFor = Exception.class)
    public void favoriteNode(Long userId, Long folderId, Long nodeId) {
        FavoriteFolder folder = getFolder(userId, folderId);
        FavoriteItem existing = itemMapper.selectOne(
                new LambdaQueryWrapper<FavoriteItem>()
                        .eq(FavoriteItem::getUserId, userId)
                        .eq(FavoriteItem::getNodeId, nodeId));
        if (existing != null) {
            if (existing.getFolderId().equals(folder.getId())) {
                return;
            }
            existing.setFolderId(folder.getId());
            existing.setUpdatedAt(LocalDateTime.now());
            itemMapper.updateById(existing);
            return;
        }
        FavoriteItem item = new FavoriteItem();
        item.setUserId(userId);
        item.setFolderId(folder.getId());
        item.setNodeId(nodeId);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        itemMapper.insert(item);
    }

    /** 一句话：移动节点到另一收藏夹。 */
    @Transactional(rollbackFor = Exception.class)
    public void moveNode(Long userId, Long nodeId, Long targetFolderId) {
        FavoriteFolder folder = getFolder(userId, targetFolderId);
        int rows = itemMapper.update(null, new LambdaUpdateWrapper<FavoriteItem>()
                .eq(FavoriteItem::getUserId, userId)
                .eq(FavoriteItem::getNodeId, nodeId)
                .set(FavoriteItem::getFolderId, folder.getId())
                .set(FavoriteItem::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw new BizException(404, "节点未收藏");
        }
    }

    /** 一句话：取消收藏节点。 */
    @Transactional(rollbackFor = Exception.class)
    public void unfavoriteNode(Long userId, Long nodeId) {
        itemMapper.delete(new LambdaQueryWrapper<FavoriteItem>()
                .eq(FavoriteItem::getUserId, userId)
                .eq(FavoriteItem::getNodeId, nodeId));
    }

    /** 一句话：获取用户所有收藏的节点 id 及其所属收藏夹 id。 */
    public Map<Long, Long> folderIdByNodeId(Long userId) {
        return itemMapper.selectList(
                        new LambdaQueryWrapper<FavoriteItem>().eq(FavoriteItem::getUserId, userId))
                .stream()
                .collect(Collectors.toMap(FavoriteItem::getNodeId, FavoriteItem::getFolderId));
    }

    /** 一句话：获取单个节点在当前用户下的收藏夹 id，未收藏返回 null。 */
    public Long folderIdForNode(Long userId, Long nodeId) {
        FavoriteItem item = itemMapper.selectOne(
                new LambdaQueryWrapper<FavoriteItem>()
                        .eq(FavoriteItem::getUserId, userId)
                        .eq(FavoriteItem::getNodeId, nodeId));
        return item == null ? null : item.getFolderId();
    }

    /** 一句话：获取指定收藏夹下的节点 id 列表。 */
    public List<Long> nodeIdsInFolder(Long userId, Long folderId) {
        getFolder(userId, folderId);
        return itemMapper.selectList(
                        new LambdaQueryWrapper<FavoriteItem>()
                                .eq(FavoriteItem::getUserId, userId)
                                .eq(FavoriteItem::getFolderId, folderId))
                .stream()
                .map(FavoriteItem::getNodeId)
                .toList();
    }

    /** 一句话：按收藏夹聚合返回用户全部收藏节点。 */
    public List<FavoriteDtos.FolderDetail> listFolderDetails(Long userId) {
        ensureDefaultFolder(userId);
        List<FavoriteFolder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<FavoriteFolder>().eq(FavoriteFolder::getUserId, userId));
        folders.sort(Comparator.comparingInt(FavoriteFolder::getSortOrder)
                .thenComparing(FavoriteFolder::getCreatedAt));
        List<Long> folderIds = folders.stream().map(FavoriteFolder::getId).toList();
        Map<Long, List<FavoriteItem>> itemsByFolder = itemMapper.selectList(
                        new LambdaQueryWrapper<FavoriteItem>()
                                .eq(FavoriteItem::getUserId, userId)
                                .in(FavoriteItem::getFolderId, folderIds.isEmpty() ? List.of(-1L) : folderIds))
                .stream()
                .collect(Collectors.groupingBy(FavoriteItem::getFolderId));
        List<FavoriteDtos.FolderDetail> result = new ArrayList<>();
        for (FavoriteFolder folder : folders) {
            List<Long> nodeIds = itemsByFolder.getOrDefault(folder.getId(), List.of())
                    .stream()
                    .map(FavoriteItem::getNodeId)
                    .toList();
            result.add(new FavoriteDtos.FolderDetail(folder.getId(), folder.getName(), folder.getSortOrder(), nodeRefs(nodeIds)));
        }
        return result;
    }

    /** 一句话：获取指定收藏夹详情（含节点简要信息）。 */
    public FavoriteDtos.FolderDetail getFolderDetail(Long userId, Long folderId) {
        FavoriteFolder folder = getFolder(userId, folderId);
        List<Long> nodeIds = nodeIdsInFolder(userId, folderId);
        return new FavoriteDtos.FolderDetail(folder.getId(), folder.getName(), folder.getSortOrder(), nodeRefs(nodeIds));
    }

    private List<FavoriteDtos.NodeRef> nodeRefs(List<Long> nodeIds) {
        if (nodeIds.isEmpty()) return List.of();
        Map<Long, TechNode> byId = nodeMapper.selectBatchIds(nodeIds).stream()
                .collect(Collectors.toMap(TechNode::getId, n -> n));
        List<FavoriteDtos.NodeRef> refs = new ArrayList<>();
        for (Long id : nodeIds) {
            TechNode node = byId.get(id);
            refs.add(new FavoriteDtos.NodeRef(id,
                    node == null ? "节点 #" + id : node.getName(),
                    node == null ? "" : node.getEra(),
                    node == null ? "" : node.getYearLabel()));
        }
        return refs;
    }

    private FavoriteFolder getFolder(Long userId, Long folderId) {
        FavoriteFolder folder = folderMapper.selectById(folderId);
        if (folder == null || !folder.getUserId().equals(userId)) {
            throw new BizException(404, "收藏夹不存在");
        }
        return folder;
    }

    private FavoriteFolder getDefaultFolder(Long userId) {
        return folderMapper.selectOne(
                new LambdaQueryWrapper<FavoriteFolder>()
                        .eq(FavoriteFolder::getUserId, userId)
                        .eq(FavoriteFolder::getName, DEFAULT_FOLDER_NAME));
    }

    private void ensureDefaultFolder(Long userId) {
        if (getDefaultFolder(userId) != null) {
            return;
        }
        FavoriteFolder folder = new FavoriteFolder();
        folder.setUserId(userId);
        folder.setName(DEFAULT_FOLDER_NAME);
        folder.setSortOrder(0);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        folderMapper.insert(folder);
    }

    private String trimName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BizException(400, "收藏夹名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64) {
            throw new BizException(400, "收藏夹名称最多 64 个字符");
        }
        return trimmed;
    }
}
