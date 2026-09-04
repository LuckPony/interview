package interview.homegrown.modules.user.service;

import interview.homegrown.infrastructure.captcha.PuzzleCaptchaService;
import interview.homegrown.modules.user.domain.AppUser;
import interview.homegrown.modules.user.domain.EmailVerifyCode;
import interview.homegrown.modules.user.repository.AppUserRepository;
import interview.homegrown.modules.user.repository.EmailVerifyCodeRepository;
import interview.homegrown.modules.user.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;


/**
 * 注册 / 邮箱验证 / 登录。
 * 铁律：密码只存 BCrypt 哈希，绝不落明文；userId 即 AppUser.id，进 JWT sub，下游业务照旧。
 * app_user 表（V12__app_user.sql）以 email 为唯一登录名。
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long CODE_TTL_MINUTES = 15;

    private final AppUserRepository userRepo;
    private final EmailVerifyCodeRepository codeRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final MailService mailService;

    private final PuzzleCaptchaService captchaService;

    public AuthService(AppUserRepository userRepo, EmailVerifyCodeRepository codeRepo,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil, MailService mailService, PuzzleCaptchaService captchaService) {
        this.userRepo = userRepo;
        this.codeRepo = codeRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.mailService = mailService;
        this.captchaService = captchaService;
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
            EmailVerifyCode rec = takeValidCode(normalized, code);
            rec.setUsed(true);
            codeRepo.save(rec);
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
    @Transactional
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
        String code = issueCode(normalized);
        if (!mailService.sendVerificationCode(normalized, code)) {
            throw new IllegalArgumentException("验证码发送失败，请稍后重试");
        }
    }

    /** 取该邮箱最新一条未使用且未过期的验证码；不合法统一抛错。 */
    private EmailVerifyCode takeValidCode(String email, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("请输入邮箱验证码");
        }
        return codeRepo.findTopByEmailAndUsedFalseOrderByIdDesc(email)
                .filter(c -> c.getExpiresAt().isAfter(Instant.now()))
                .filter(c -> c.getCode().equals(code.trim()))
                .orElseThrow(() -> new IllegalArgumentException("验证码错误或已过期，请重新获取"));
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
        EmailVerifyCode rec = takeValidCode(normalized, code);
        rec.setUsed(true);
        codeRepo.save(rec);
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

    private String issueCode(String email) {
        // 重发时先作废旧的未使用验证码，保证只有最新一条有效，避免输错“上一封”的码
        codeRepo.invalidateUnused(email);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        EmailVerifyCode rec = new EmailVerifyCode();
        rec.setEmail(email);
        rec.setCode(code);
        rec.setExpiresAt(Instant.now().plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES));
        codeRepo.save(rec);
        return code;
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
