package interview.homegrown.modules.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 注册账号。id 即下游各业务表的 userId（JWT sub）。
 * 对应 app_user 表：基础字段由 V12__app_user.sql 创建，
 * 资料字段由 V17__app_user_profile_fields.sql 扩展（email 唯一登录）。
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录邮箱（唯一）。 */
    @Column(nullable = false, unique = true, length = 128)
    private String email;

    /** BCrypt 哈希，绝不存明文。 */
    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    /** 展示名称。 */
    @Column(name = "display_name", length = 64)
    private String displayName;

    /** 邮箱是否已验证。 */
    @Column(nullable = false)
    private boolean verified = false;

    /** 登录名（可空：当前登录仍走 email，username 预留）。 */
    @Column(length = 50)
    private String username;

    /** 昵称。 */
    @Column(length = 50)
    private String nickname;

    /** 角色：USER / ADMIN。 */
    @Column(nullable = false, length = 20)
    private String role = "USER";

    /** 性别：M / F / 空。 */
    @Column(length = 10)
    private String gender;

    /** 手机号。 */
    @Column(length = 20)
    private String phone;

    /** 生日。 */
    private LocalDate birthday;

    /** 头像 URL。 */
    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    /** 1 正常 / 0 禁用。 */
    @Column(nullable = false)
    private Short status = 1;

    /** 扩展信息（JSON）。 */
    @Column(name = "extra_info", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String extraInfo;

    /** 最近登录时间。 */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
