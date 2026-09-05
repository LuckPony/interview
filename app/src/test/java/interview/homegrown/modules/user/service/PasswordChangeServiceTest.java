package interview.homegrown.modules.user.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.infrastructure.captcha.PuzzleCaptchaService;
import interview.homegrown.modules.user.domain.AppUser;
import interview.homegrown.modules.user.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordChangeServiceTest {

    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final PuzzleCaptchaService captchaService = mock(PuzzleCaptchaService.class);
    private final EmailVerificationCodeService verificationCodeService = mock(EmailVerificationCodeService.class);
    private final MailService mailService = mock(MailService.class);
    private final PasswordChangeService service = new PasswordChangeService(
            userRepository,
            passwordEncoder,
            captchaService,
            verificationCodeService,
            mailService
    );

    @Test
    @DisplayName("拼图通过后向当前账号邮箱发送改密验证码")
    void shouldSendCodeAfterCaptchaPasses() {
        AppUser user = user(2L, "tester@example.com", "old-hash");
        when(captchaService.isEnabled()).thenReturn(true);
        when(captchaService.consumeTicket("ticket")).thenReturn(true);
        when(mailService.isConfigured()).thenReturn(true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(verificationCodeService.issue("tester@example.com")).thenReturn("123456");
        when(mailService.sendPasswordChangeCode("tester@example.com", "123456")).thenReturn(true);

        PasswordChangeService.PasswordCodeResult result = service.sendCode(2L, "ticket");

        assertThat(result.emailHint()).isEqualTo("te***@example.com");
        verify(captchaService).consumeTicket("ticket");
        verify(mailService).sendPasswordChangeCode("tester@example.com", "123456");
    }

    @Test
    @DisplayName("拼图凭证无效时不会签发邮箱验证码")
    void shouldRejectInvalidCaptchaTicket() {
        when(captchaService.isEnabled()).thenReturn(true);
        when(captchaService.consumeTicket("invalid")).thenReturn(false);

        assertThatThrownBy(() -> service.sendCode(2L, "invalid"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("图像拼图验证");
        verify(verificationCodeService, never()).issue("tester@example.com");
    }

    @Test
    @DisplayName("验证码正确时会使用 BCrypt 哈希保存新密码")
    void shouldChangePasswordAfterCodePasses() {
        AppUser user = user(2L, "tester@example.com", "old-hash");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("NewPass123", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewPass123")).thenReturn("new-hash");

        service.change(2L, "123456", "NewPass123");

        verify(verificationCodeService).consume("tester@example.com", "123456");
        verify(userRepository).save(user);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    @DisplayName("新密码与当前密码相同时拒绝保存")
    void shouldRejectSamePassword() {
        AppUser user = user(2L, "tester@example.com", "old-hash");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("SamePass1", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.change(2L, "123456", "SamePass1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能与当前密码相同");
        verify(userRepository, never()).save(user);
    }

    private AppUser user(Long id, String email, String passwordHash) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        return user;
    }
}
