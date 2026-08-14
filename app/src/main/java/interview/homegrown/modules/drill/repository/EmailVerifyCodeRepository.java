package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.EmailVerifyCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerifyCodeRepository extends JpaRepository<EmailVerifyCode, Long> {

    /** 取该邮箱最新一条未使用的验证码（验证时用）。 */
    Optional<EmailVerifyCode> findTopByEmailAndUsedFalseOrderByIdDesc(String email);
}
