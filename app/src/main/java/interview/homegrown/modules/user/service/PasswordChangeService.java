package interview.homegrown.modules.user.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.infrastructure.captcha.PuzzleCaptchaService;
import interview.homegrown.modules.user.domain.AppUser;
import interview.homegrown.modules.user.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordChangeService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PuzzleCaptchaService captchaService;
    private final EmailVerificationCodeService verificationCodeService;
    private final MailService mailService;

    public PasswordChangeService(AppUserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 PuzzleCaptchaService captchaService,
                                 EmailVerificationCodeService verificationCodeService,
                                 MailService mailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.captchaService = captchaService;
        this.verificationCodeService = verificationCodeService;
        this.mailService = mailService;
    }

    /** 拼图凭证只使用一次；验证码先落库，再在事务外发送邮件。 */
    public PasswordCodeResult sendCode(Long userId, String captchaToken) {
        if (captchaService.isEnabled() && !captchaService.consumeTicket(captchaToken)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先完成图像拼图验证");
        }
        if (!mailService.isConfigured()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "当前环境未配置邮件服务，暂时无法修改密码");
        }
        AppUser user = findUser(userId);
        String code = verificationCodeService.issue(user.getEmail());
        if (!mailService.sendPasswordChangeCode(user.getEmail(), code)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "验证码发送失败，请稍后重试");
        }
        return new PasswordCodeResult(maskEmail(user.getEmail()));
    }

    @Transactional
    public void change(Long userId, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码长度需为 6-64 位");
        }
        AppUser user = findUser(userId);
        verificationCodeService.consume(user.getEmail(), code);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码不能与当前密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private AppUser findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(0, at));
        String name = email.substring(0, at);
        String visible = name.substring(0, Math.min(2, name.length()));
        return visible + "***" + email.substring(at);
    }

    public record PasswordCodeResult(String emailHint) {}
}
