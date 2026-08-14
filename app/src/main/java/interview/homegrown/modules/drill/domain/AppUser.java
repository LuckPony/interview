package interview.homegrown.modules.drill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 注册账号。id 即下游各业务表的 userId（JWT sub）。 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String email;

    /** BCrypt 哈希，绝不存明文。 */
    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
