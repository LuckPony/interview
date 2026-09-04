package interview.homegrown.modules.user.repository;

import interview.homegrown.modules.user.domain.EmailVerifyCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmailVerifyCodeRepository extends JpaRepository<EmailVerifyCode, Long> {

    /** 取该邮箱最新一条未使用的验证码（验证时用）。 */
    Optional<EmailVerifyCode> findTopByEmailAndUsedFalseOrderByIdDesc(String email);

    /** 重发验证码时作废该邮箱旧的未使用验证码，保证只有最新一条有效。 */
    @Modifying
    @Query("update EmailVerifyCode c set c.used = true where c.email = :email and c.used = false")
    void invalidateUnused(@Param("email") String email);
}
