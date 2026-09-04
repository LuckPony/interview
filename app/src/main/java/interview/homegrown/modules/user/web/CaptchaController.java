package interview.homegrown.modules.user.web;

import interview.homegrown.infrastructure.captcha.PuzzleCaptchaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图像拼图滑块验证：GET 出题（挖槽底图 + 拼块），POST 校验并换取一次性 ticket。
 * 注册/登录等敏感动作在业务接口里核销 ticket，防止脚本跳过滑块直连。
 */
@RestController
@RequestMapping("/api/auth/captcha")
public class CaptchaController {

    private final PuzzleCaptchaService captcha;

    public CaptchaController(PuzzleCaptchaService captcha) {
        this.captcha = captcha;
    }

    /** 出题：返回 captchaId + 底图/拼块（base64）。目标 X 只存服务端。 */
    @GetMapping
    public PuzzleCaptchaService.CaptchaIssue issue() {
        return captcha.issue();
    }

    /** 校验：x 为拼块最终左移量。通过返回一次性 captchaToken。 */
    @PostMapping("/verify")
    public VerifyView verify(@Valid @RequestBody VerifyRequest req) {
        String token = captcha.verify(req.captchaId(), req.x());
        return new VerifyView(true, token);
    }

    public record VerifyRequest(
            @NotBlank(message = "缺少验证码编号") String captchaId,
            @NotNull(message = "缺少滑动位置") Integer x) {}

    public record VerifyView(boolean pass, String captchaToken) {}
}