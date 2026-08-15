package interview.homegrown.modules.user.web;

import interview.homegrown.modules.user.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 注册 / 邮箱验证 / 登录。成功后统一签发 JWT（userId 写入 token 的 sub）。
 * 旧的「?userId= 直接签发」演示登录已移除，改为真实账号体系。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthView register(@Valid @RequestBody CredentialsRequest req) {
        AuthService.AuthResult r = authService.register(req.email(), req.password());
        return new AuthView(r.token(), String.valueOf(r.userId()), r.verified());
    }

    @PostMapping("/verify")
    public AuthView verify(@Valid @RequestBody VerifyRequest req) {
        AuthService.AuthResult r = authService.verify(req.email(), req.code());
        return new AuthView(r.token(), String.valueOf(r.userId()), r.verified());
    }

    @PostMapping("/login")
    public AuthView login(@Valid @RequestBody CredentialsRequest req) {
        AuthService.AuthResult r = authService.login(req.email(), req.password());
        return new AuthView(r.token(), String.valueOf(r.userId()), r.verified());
    }

    public record CredentialsRequest(
            @NotBlank @Email(message = "邮箱格式不正确") String email,
            @NotBlank @Size(min = 6, max = 64, message = "密码长度需 6-64 位") String password) {}

    public record VerifyRequest(
            @NotBlank @Email(message = "邮箱格式不正确") String email,
            @NotBlank(message = "请输入验证码") String code) {}

    public record AuthView(String token, String userId, boolean verified) {}
}
