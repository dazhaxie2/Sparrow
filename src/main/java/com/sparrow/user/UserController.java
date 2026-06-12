package com.sparrow.user;

import com.sparrow.common.ApiResponse;
import com.sparrow.common.UserContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@Validated
public class UserController {

    public record AuthRequest(@NotBlank @Size(min = 3, max = 32) String username,
                              @NotBlank @Size(min = 6, max = 64) String password) {
    }

    public record Profile(Long id, String username, boolean member, LocalDateTime memberExpireAt) {
    }

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, String>> register(@RequestBody @Validated AuthRequest req) {
        return ApiResponse.ok(Map.of("token", userService.register(req.username(), req.password())));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@RequestBody @Validated AuthRequest req) {
        return ApiResponse.ok(Map.of("token", userService.login(req.username(), req.password())));
    }

    @GetMapping("/me")
    public ApiResponse<Profile> me() {
        User user = userService.getById(UserContext.require());
        return ApiResponse.ok(new Profile(user.getId(), user.getUsername(),
                user.memberActive(), user.getMemberExpireAt()));
    }
}
