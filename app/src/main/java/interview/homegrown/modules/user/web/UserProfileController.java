package interview.homegrown.modules.user.web;

import interview.homegrown.common.result.Result;
import interview.homegrown.modules.user.service.UserProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/user/profile")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    public Result<UserProfileService.UserProfile> get() {
        return Result.success(userProfileService.get(uid()));
    }

    @PutMapping
    public Result<UserProfileService.UserProfile> update(@Valid @RequestBody UpdateProfileRequest request) {
        UserProfileService.UpdateProfile command = new UserProfileService.UpdateProfile(
                request.username(),
                request.nickname(),
                request.gender(),
                request.phone(),
                request.birthday()
        );
        return Result.success(userProfileService.update(uid(), command));
    }

    private Long uid() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

    public record UpdateProfileRequest(
            @Size(max = 50, message = "用户名最多 50 个字符") String username,
            @Size(max = 50, message = "昵称最多 50 个字符") String nickname,
            String gender,
            @Size(max = 20, message = "手机号最多 20 个字符") String phone,
            LocalDate birthday
    ) {}
}
