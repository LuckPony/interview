package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.AppUser;
import interview.homegrown.modules.drill.domain.EmailVerifyCode;
import interview.homegrown.modules.drill.repository.AppUserRepository;
import interview.homegrown.modules.drill.repository.EmailVerifyCodeRepository;
import interview.homegrown.modules.drill.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 注册 / 邮箱验证 / 登录。
 * 铁律：密码只存 BCrypt 哈希，绝不落明文；userId 即 AppUser.id，进 JWT sub，下游业务照旧。
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
        user.setEmail(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
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
