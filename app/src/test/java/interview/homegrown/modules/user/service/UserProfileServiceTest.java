package interview.homegrown.modules.user.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.modules.user.domain.AppUser;
import interview.homegrown.modules.user.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserProfileServiceTest {

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final UserProfileService service = new UserProfileService(repository);

    @Test
    @DisplayName("保存个人资料时会清理首尾空白并返回最新信息")
    void shouldUpdateAndNormalizeProfile() {
        AppUser user = new AppUser();
        user.setId(2L);
        user.setEmail("user@example.com");
        when(repository.findById(2L)).thenReturn(Optional.of(user));
        when(repository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDate birthday = LocalDate.of(2000, 1, 2);
        UserProfileService.UserProfile result = service.update(2L, new UserProfileService.UpdateProfile(
                "  面霸用户  ",
                "  小霸  ",
                "OTHER",
                "  13800000000  ",
                birthday
        ));

        assertThat(result.username()).isEqualTo("面霸用户");
        assertThat(result.nickname()).isEqualTo("小霸");
        assertThat(result.gender()).isEqualTo("OTHER");
        assertThat(result.phone()).isEqualTo("13800000000");
        assertThat(result.birthday()).isEqualTo(birthday);
    }

    @Test
    @DisplayName("拒绝不支持的性别参数")
    void shouldRejectUnsupportedGender() {
        AppUser user = new AppUser();
        user.setId(2L);
        when(repository.findById(2L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.update(2L, new UserProfileService.UpdateProfile(
                "用户",
                "",
                "UNKNOWN",
                "",
                null
        ))).isInstanceOf(BusinessException.class);
    }
}
