package interview.homegrown.modules.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮箱验证码发送。SMTP 未配置（spring.mail.host / app.mail.from 为空）时跳过发送，
 * 由 {@link AuthService} 决定是否走「自动通过」分支。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public MailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                       @Value("${app.mail.from:}") String from) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.from = from == null ? "" : from.trim();
    }

    /** SMTP 是否就绪：有 JavaMailSender Bean 且配置了发件人。 */
    public boolean isConfigured() {
        return mailSender != null && !from.isEmpty();
    }

    /** 发送验证码。失败返回 false，调用方据此降级为自动通过。 */
    public boolean sendVerificationCode(String to, String code) {
        return sendCode(to, code, "面霸 · 邮箱验证码", "你的面霸账号验证码是：");
    }

    /** 修改密码验证码使用独立主题，用户能明确识别这封安全邮件的用途。 */
    public boolean sendPasswordChangeCode(String to, String code) {
        return sendCode(to, code, "面霸 · 修改密码验证码", "你正在修改面霸账号密码，验证码是：");
    }

    private boolean sendCode(String to, String code, String subject, String prefix) {
        if (!isConfigured()) {
            log.info("SMTP 未配置，跳过发送验证码给 {}（验证码：{}）", to, code);
            return false;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(prefix + code + "，15 分钟内有效。若非本人操作，请忽略此邮件。");
            mailSender.send(msg);
            log.info("已发送验证码到 {}", to);
            return true;
        } catch (Exception e) {
            log.warn("发送验证码失败（{}）：{}", to, e.getMessage());
            return false;
        }
    }
}
