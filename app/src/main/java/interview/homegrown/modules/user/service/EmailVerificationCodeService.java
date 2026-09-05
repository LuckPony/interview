package interview.homegrown.modules.user.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.user.domain.EmailVerifyCode;
import interview.homegrown.modules.user.repository.EmailVerifyCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 邮箱验证码的统一签发与消费能力。
 *
 * <p>注册、改密等业务只负责各自的权限与流程，本服务统一处理验证码的唯一有效性、
 * 有效期和一次性消费，避免不同业务各写一套容易产生行为差异。
 */
@Service
public class EmailVerificationCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long CODE_TTL_MINUTES = 15;

    private final EmailVerifyCodeRepository codeRepository;

    public EmailVerificationCodeService(EmailVerifyCodeRepository codeRepository) {
        this.codeRepository = codeRepository;
    }

    @Transactional
    public String issue(String email) {
        codeRepository.invalidateUnused(email);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        EmailVerifyCode record = new EmailVerifyCode();
        record.setEmail(email);
        record.setCode(code);
        record.setExpiresAt(Instant.now().plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES));
        codeRepository.save(record);
        return code;
    }

    @Transactional
    public void consume(String email, String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入邮箱验证码");
        }
        EmailVerifyCode record = codeRepository.findTopByEmailAndUsedFalseOrderByIdDesc(email)
                .filter(item -> item.getExpiresAt().isAfter(Instant.now()))
                .filter(item -> item.getCode().equals(code.trim()))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "验证码错误或已过期，请重新获取"
                ));
        record.setUsed(true);
        codeRepository.save(record);
    }
}
