package interview.homegrown.modules.user.service;

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
 *
 * users 表（V2__add_user_module.sql）以 username 为唯一登录名，
 * 但前端契约仍是 email + password 登录，故注册时由 email 派生 username（保证唯一）。
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long CODE_TTL_MINUTES = 15;
    private static final int USERNAME_MAX_LEN = 50;

    private final AppUserRepository userRepo;
    private final EmailVerifyCodeRepository codeRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final MailService mailService;

    public AuthService(AppUserRepository userRepo, EmailVerifyCodeRepository codeRepo,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil, MailService mailService) {
        this.userRepo = userRepo;
        this.codeRepo = codeRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.mailService = mailService;
    }

    @Transactional
    public AuthResult register(String email, String password) {
        String normalized = normalizeEmail(email);
        if (userRepo.existsByEmail(normalized)) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        AppUser user = new AppUser();
        user.setUsername(deriveUsername(normalized));
        user.setEmail(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole("USER");
        user.setStatus((short) 1);
        // SMTP 就绪 → 需邮箱验证；否则（本地/演示）自动通过
        boolean needVerify = mailService.isConfigured();
        user.setVerified(!needVerify);
        userRepo.save(user);

        if (needVerify) {
            String code = issueCode(normalized);
            boolean sent = mailService.sendVerificationCode(normalized, code);
            if (!sent) {
                // 邮件发不出去别把用户卡死：降级为自动通过
                user.setVerified(true);
                userRepo.save(user);
            }
        }
        return new AuthResult(jwtUtil.generateToken(user.getId()), user.getId(), user.isVerified());
    }

    @Transactional
    public AuthResult verify(String email, String code) {
        String normalized = normalizeEmail(email);
        AppUser user = userRepo.findByEmail(normalized)
                .orElseThrow(() -> new IllegalArgumentException("该邮箱尚未注册"));
        EmailVerifyCode rec = codeRepo.findTopByEmailAndUsedFalseOrderByIdDesc(normalized)
                .filter(c -> c.getExpiresAt().isAfter(Instant.now()))
                .filter(c -> c.getCode().equals(code.trim()))
                .orElseThrow(() -> new IllegalArgumentException("验证码错误或已过期"));
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

    /**
     * 由 email 派生唯一 username：取 @ 前的本地部分，清理非法字符、截断到 50，
     * 若与已有 username 冲突则追加 -数字 后缀。
     */
    private String deriveUsername(String email) {
        String base = email.substring(0, email.indexOf('@'));
        String cleaned = base.replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (cleaned.length() > USERNAME_MAX_LEN) {
            cleaned = cleaned.substring(0, USERNAME_MAX_LEN);
        }
        if (cleaned.isBlank()) {
            cleaned = "user";
        }
        String candidate = cleaned;
        int suffix = 1;
        while (userRepo.existsByUsername(candidate)) {
            String tail = "-" + suffix++;
            int maxBase = USERNAME_MAX_LEN - tail.length();
            candidate = (cleaned.length() > maxBase ? cleaned.substring(0, maxBase) : cleaned) + tail;
        }
        return candidate;
    }

    private String issueCode(String email) {
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
