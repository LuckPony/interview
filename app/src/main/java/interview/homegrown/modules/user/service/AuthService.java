package interview.homegrown.modules.user.service;

import interview.homegrown.infrastructure.captcha.PuzzleCaptchaService;
import interview.homegrown.modules.user.domain.AppUser;
import interview.homegrown.modules.user.repository.AppUserRepository;
import interview.homegrown.modules.user.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册 / 邮箱验证 / 登录。
 * 铁律：密码只存 BCrypt 哈希，绝不落明文；userId 即 AppUser.id，进 JWT sub，下游业务照旧。
 * app_user 表（V12__app_user.sql）以 email 为唯一登录名。
 */
@Service
public class AuthService {

    private final AppUserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final MailService mailService;
    private final PuzzleCaptchaService captchaService;
    private final EmailVerificationCodeService verificationCodeService;

    public AuthService(AppUserRepository userRepo,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       MailService mailService,
                       PuzzleCaptchaService captchaService,
                       EmailVerificationCodeService verificationCodeService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.mailService = mailService;
        this.captchaService = captchaService;
        this.verificationCodeService = verificationCodeService;
    }

    // ===== 注册：SMTP 就绪时必须在提交时校验邮箱验证码，通过才创建账号 =====
    // 关键语义：账号只在用户提交「邮箱+密码+验证码」时入库；
    // 未输入验证码/验证失败绝不落库（修复“空邮箱占坑、下次注册提示已存在”的 bug）。
    @Transactional
    public AuthResult register(String email, String password, String code) {
        String normalized = normalizeEmail(email);
        if (userRepo.existsByEmail(normalized)) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        // SMTP 就绪：校验并消费发码接口下发的验证码；
        // SMTP 未配（本地/演示）：无需验证码，直接注册成功。
        if (mailService.isConfigured()) {
            verificationCodeService.consume(normalized, code);
        }
        AppUser user = new AppUser();
        user.setEmail(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setVerified(true);   // 走到这里即代表邮箱已验证，注册即生效
        userRepo.save(user);
        return new AuthResult(jwtUtil.generateToken(user.getId()), user.getId(), true);
    }

    /**
     * 注册前置发码：先过滑块（人机闸门）→ 再校验邮箱未被注册 → 最后发送验证码。
     * 这里【不创建账号】，账号只在 register(邮箱+密码+验证码) 时创建。
     */
    public void sendRegisterCode(String email, String captchaToken) {
        String normalized = normalizeEmail(email);
        // 人机闸门放在最前：滑块开启时必须先持有一次性 captchaToken
        // （同时避免把本接口变成“邮箱是否已注册”的探测接口）
        if (captchaService.isEnabled() && !captchaService.consumeTicket(captchaToken)) {
            throw new IllegalArgumentException("请先完成滑块验证");
        }
        if (!mailService.isConfigured()) {
            throw new IllegalArgumentException("当前环境未启用邮箱验证，无需获取验证码");
        }
        if (userRepo.existsByEmail(normalized)) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        String code = verificationCodeService.issue(normalized);
        if (!mailService.sendVerificationCode(normalized, code)) {
            throw new IllegalArgumentException("验证码发送失败，请稍后重试");
        }
    }

    /** 供 /api/auth/config 告知前端本环境是否需要邮箱验证码。 */
    public boolean mailConfigured() {
        return mailService.isConfigured();
    }

    @Transactional
    public AuthResult verify(String email, String code) {
        String normalized = normalizeEmail(email);
        AppUser user = userRepo.findByEmail(normalized)
                .orElseThrow(() -> new IllegalArgumentException("该邮箱尚未注册"));
        verificationCodeService.consume(normalized, code);
        user.setVerified(true);
        userRepo.save(user);
        return new AuthResult(jwtUtil.generateToken(user.getId()), user.getId(), true);
    }

    public AuthResult login(String email, String password) {
        String normalized = normalizeEmail(email);
        AppUser user = userRepo.findByEmail(normalized)
                .orElseThrow(() -> new IllegalArgumentException("邮箱或密码错误"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("邮箱或密码错误");
        }
        return new AuthResult(jwtUtil.generateToken(user.getId()), user.getId(), user.isVerified());
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        return email.trim().toLowerCase();
    }

    /** 注册/验证/登录成功后的统一返回：JWT + userId + 是否已验证邮箱。 */
    public record AuthResult(String token, Long userId, boolean verified) {}
}
