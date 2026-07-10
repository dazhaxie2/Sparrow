package com.sparrow.user.infrastructure.mail;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 验证码邮件发送适配器。
 *
 * <p><b>为何独立成 Bean:</b> {@code @Async} 基于 Spring 代理,同类内 this 调用会绕过代理→同步执行。
 * 邮件发送若在 UserService 内部 this 调用,SMTP 握手(连接 smtp.qq.com:465 + SSL,常 3-10s 甚至超时)
 * 会阻塞 HTTP 请求线程,导致网关/CDN 断开连接(net::ERR_CONNECTION_CLOSED)。
 * 抽到独立 Bean 后,UserService 跨 Bean 调用本类,代理生效,真正异步。
 */
@Component
public class MailSenderAdapter {

    private static final Logger log = LoggerFactory.getLogger(MailSenderAdapter.class);

    private final JavaMailSender mailSender;
    /** 发件地址:QQ SMTP 要求 From 必须等于登录账号(spring.mail.username)。 */
    private final String fromAddress;
    private final String fromPersonal;
    private final int codeTtlMinutes;

    public MailSenderAdapter(JavaMailSender mailSender,
                             @Value("${spring.mail.username:}") String fromAddress,
                             @Value("${sparrow.mail.from-personal:Sparrow 科技图}") String fromPersonal,
                             @Value("${sparrow.mail.code-ttl-minutes:5}") int codeTtlMinutes) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress == null || fromAddress.isBlank() ? "noreply@sparrow.tech" : fromAddress;
        this.fromPersonal = fromPersonal;
        this.codeTtlMinutes = codeTtlMinutes;
    }

    /** 验证码 TTL(分钟),供 UserService 写 Redis 过期时间用,保持两端一致。 */
    public int getCodeTtlMinutes() {
        return codeTtlMinutes;
    }

    /**
     * 异步发送验证码邮件。跨 Bean 调用本方法时 @Async 生效,SMTP 阻塞不拖累 HTTP 线程。
     * 失败仅记录日志,不抛异常——验证码已存 Redis,用户可在有效期内重发或换密码登录。
     */
    @Async
    public void sendVerificationCode(String toEmail, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(new InternetAddress(fromAddress, fromPersonal));
            helper.setTo(toEmail);
            helper.setSubject("【Sparrow 科技图】登录验证码");
            helper.setText("您正在登录 Sparrow 科技图。验证码：<b>" + code + "</b>，"
                    + codeTtlMinutes + " 分钟内有效。如非本人操作请忽略本邮件。", true);
            mailSender.send(message);
            log.info("验证码邮件已发送: {}", toEmail);
        } catch (Exception e) {
            // SMTP 凭证错误/网络异常:仅记录,不冒泡(验证码已存 Redis,不阻塞流程)
            log.error("验证码邮件发送失败 to={}: {}", toEmail, e.getMessage());
        }
    }
}
