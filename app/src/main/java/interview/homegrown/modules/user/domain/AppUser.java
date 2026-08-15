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
 * 对应 V2__add_user_module.sql 创建的 users 表。
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录名（唯一），注册时由 email 派生。 */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt 哈希，绝不存明文。 */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 昵称。 */
    @Column(length = 50)
    private String nickname;

    /** 角色：USER / ADMIN。 */
    @Column(nullable = false, length = 20)
    private String role = "USER";

    /** 性别：M / F / 空。 */
    @Column(length = 10)
    private String gender;

    /** 登录邮箱（现有认证流程用它）。 */
    @Column(length = 100)
    private String email;

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

    /** 邮箱是否已验证（V14 迁移补充的列）。 */
    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
