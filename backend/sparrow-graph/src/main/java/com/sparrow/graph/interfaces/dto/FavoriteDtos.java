package com.sparrow.graph.interfaces.dto;

import java.util.List;

public final class FavoriteDtos {

    private FavoriteDtos() {
    }

    public record FolderBrief(Long id, String name, Integer sortOrder) {
    }

    public record NodeRef(Long id, String name, String era, String yearLabel) {
    }

    public record FolderDetail(Long id, String name, Integer sortOrder, List<NodeRef> nodes) {
    }

    public record CreateFolderRequest(String name) {
    }

    public record RenameFolderRequest(String name) {
    }

    public record FavoriteNodeRequest(Long folderId, Long nodeId) {
    }

    public record MoveNodeRequest(Long nodeId, Long targetFolderId) {
    }
}
