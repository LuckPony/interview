package interview.homegrown.modules.user.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.user.domain.AppUser;
import interview.homegrown.modules.user.repository.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class UserProfileService {

    private final AppUserRepository userRepository;

    public UserProfileService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserProfile get(Long userId) {
        return toProfile(findUser(userId));
    }

    @Transactional
    public UserProfile update(Long userId, UpdateProfile command) {
        AppUser user = findUser(userId);
        user.setUsername(trimToNull(command.username()));
        user.setNickname(trimToNull(command.nickname()));
        user.setGender(normalizeGender(command.gender()));
        user.setPhone(trimToNull(command.phone()));
        user.setBirthday(command.birthday());
        return toProfile(userRepository.save(user));
    }

    private AppUser findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private String normalizeGender(String gender) {
        String value = trimToNull(gender);
        if (value == null) return null;
        if (!"M".equals(value) && !"F".equals(value) && !"OTHER".equals(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "性别参数不正确");
        }
        return value;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UserProfile toProfile(AppUser user) {
        return new UserProfile(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getNickname(),
                user.getGender(),
                user.getPhone(),
                user.getBirthday()
        );
    }

    public record UpdateProfile(
            String username,
            String nickname,
            String gender,
            String phone,
            LocalDate birthday
    ) {}

    public record UserProfile(
            Long id,
            String email,
            String username,
            String nickname,
            String gender,
            String phone,
            LocalDate birthday
    ) {}
}
