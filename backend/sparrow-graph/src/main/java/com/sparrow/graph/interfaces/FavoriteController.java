package com.sparrow.graph.interfaces;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.security.UserContext;
import com.sparrow.graph.application.FavoriteService;
import com.sparrow.graph.interfaces.dto.FavoriteDtos;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 一句话：用户收藏夹与节点收藏的 REST 入口。 */
@RestController
@RequestMapping("/api/graph/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @GetMapping("/folders")
    public ApiResponse<List<FavoriteDtos.FolderBrief>> listFolders() {
        return ApiResponse.ok(favoriteService.listFolders(UserContext.require()));
    }

    @PostMapping("/folders")
    public ApiResponse<FavoriteDtos.FolderBrief> createFolder(@RequestBody FavoriteDtos.CreateFolderRequest request) {
        return ApiResponse.ok(favoriteService.createFolder(UserContext.require(), request.name()));
    }

    @PutMapping("/folders/{folderId}")
    public ApiResponse<FavoriteDtos.FolderBrief> renameFolder(
            @PathVariable Long folderId,
            @RequestBody FavoriteDtos.RenameFolderRequest request) {
        return ApiResponse.ok(favoriteService.renameFolder(UserContext.require(), folderId, request.name()));
    }

    @DeleteMapping("/folders/{folderId}")
    public ApiResponse<Void> deleteFolder(@PathVariable Long folderId) {
        favoriteService.deleteFolder(UserContext.require(), folderId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/folders/{folderId}")
    public ApiResponse<FavoriteDtos.FolderDetail> getFolderDetail(@PathVariable Long folderId) {
        return ApiResponse.ok(favoriteService.getFolderDetail(UserContext.require(), folderId));
    }

    @GetMapping("/details")
    public ApiResponse<List<FavoriteDtos.FolderDetail>> listDetails() {
        return ApiResponse.ok(favoriteService.listFolderDetails(UserContext.require()));
    }

    @PostMapping("/items")
    public ApiResponse<Void> favoriteNode(@RequestBody FavoriteDtos.FavoriteNodeRequest request) {
        favoriteService.favoriteNode(UserContext.require(), request.folderId(), request.nodeId());
        return ApiResponse.ok(null);
    }

    @PutMapping("/items/move")
    public ApiResponse<Void> moveNode(@RequestBody FavoriteDtos.MoveNodeRequest request) {
        favoriteService.moveNode(UserContext.require(), request.nodeId(), request.targetFolderId());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/items/{nodeId}")
    public ApiResponse<Void> unfavoriteNode(@PathVariable Long nodeId) {
        favoriteService.unfavoriteNode(UserContext.require(), nodeId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/map")
    public ApiResponse<Map<Long, Long>> folderIdByNodeId() {
        return ApiResponse.ok(favoriteService.folderIdByNodeId(UserContext.require()));
    }
}
