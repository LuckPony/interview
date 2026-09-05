package interview.homegrown.modules.user.web;

import interview.homegrown.common.result.Result;
import interview.homegrown.modules.user.service.PasswordChangeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/password")
public class UserPasswordController {

    private final PasswordChangeService passwordChangeService;

    public UserPasswordController(PasswordChangeService passwordChangeService) {
        this.passwordChangeService = passwordChangeService;
    }

    @PostMapping("/code")
    public Result<PasswordChangeService.PasswordCodeResult> sendCode(
            @Valid @RequestBody SendPasswordCodeRequest request) {
        return Result.success(passwordChangeService.sendCode(uid(), request.captchaToken()));
    }

    @PutMapping
    public Result<Void> change(@Valid @RequestBody ChangePasswordRequest request) {
        passwordChangeService.change(uid(), request.code(), request.newPassword());
        return Result.success();
    }

    private Long uid() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

    public record SendPasswordCodeRequest(String captchaToken) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "请输入邮箱验证码") String code,
            @NotBlank(message = "请输入新密码")
            @Size(min = 6, max = 64, message = "新密码长度需为 6-64 位") String newPassword
    ) {}
}
