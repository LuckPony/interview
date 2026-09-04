package interview.homegrown.modules.user.web;

import interview.homegrown.infrastructure.captcha.PuzzleCaptchaService;
import interview.homegrown.modules.user.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/**
 * 注册 / 邮箱验证 / 登录。成功后统一签发 JWT（userId 写入 token 的 sub）。
 * 旧的「?userId= 直接签发」演示登录已移除，改为真实账号体系。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PuzzleCaptchaService captchaService;

    public AuthController(AuthService authService,PuzzleCaptchaService captchaService) {
        this.authService = authService;
        this.captchaService = captchaService;
    }

    // ===== register：提交时带邮箱验证码，验证通过才创建账号 =====
    @PostMapping("/register")
    public AuthView register(@Valid @RequestBody RegisterRequest req) {
        AuthService.AuthResult r = authService.register(req.email(), req.password(), req.code());
        return new AuthView(r.token(), String.valueOf(r.userId()), r.verified());
    }

    // ===== 注册前置发码：滑块通过后由前端调用，向邮箱发验证码（不创建账号） =====
    @PostMapping("/send-register-code")
    public void sendRegisterCode(@Valid @RequestBody SendCodeRequest req) {
        authService.sendRegisterCode(req.email(), req.captchaToken());
    }

    // ===== 前端询问本环境是否需要滑块 / 邮箱验证 =====
    @GetMapping("/config")
    public ConfigView config() {
        return new ConfigView(captchaService.isEnabled(), authService.mailConfigured());
    }

    // ===== 请求体：code 在 SMTP 未配置的演示环境可空；captchaToken 在滑块关闭时可空 =====
    public record RegisterRequest(
            @NotBlank @Email(message = "邮箱格式不正确") String email,
            @NotBlank @Size(min = 6, max = 64, message = "密码长度需 6-64 位") String password,
            String code) {}

    public record SendCodeRequest(
            @NotBlank @Email(message = "邮箱格式不正确") String email,
            String captchaToken) {}

    public record ConfigView(boolean captchaRequired, boolean emailVerifyRequired) {}

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
