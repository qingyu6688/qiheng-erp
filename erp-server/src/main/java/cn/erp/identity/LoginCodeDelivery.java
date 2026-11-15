package cn.erp.identity;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;

@Component
class LoginCodeDelivery {
    private static final String SMS_HOST = "dysmsapi.aliyuncs.com";
    private static final String SMS_VERSION = "2017-05-25";
    private static final String SIGN_ALGORITHM = "ACS3-HMAC-SHA256";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String SMTP_TIMEOUT = "5000";

    private final ObjectMapper json;
    private final LoginSettingsService settings;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT).version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    LoginCodeDelivery(ObjectMapper json, LoginSettingsService settings) {
        this.json = json;
        this.settings = settings;
    }

    void send(String method, LoginSettingsService.Provider provider, String destination, String code) {
        if (code == null || !code.matches("[0-9]{6}")) throw deliveryFailure();
        switch (method) {
            case "sms" -> sendSms(provider, destination, code);
            case "email" -> sendEmail(provider, destination, code);
            default -> throw new ApiException(400, "VALIDATION_FAILED", "验证码发送方式无效");
        }
    }

    private void sendSms(LoginSettingsService.Provider provider, String destination, String code) {
        if (destination == null || !destination.matches("\\+861[3-9][0-9]{9}")) throw deliveryFailure();
        var config = provider.config();
        // 阿里云国内短信接受不含国家码的手机号，签名和发送共用同一份排序后的编码串。
        String query = query(Map.of("PhoneNumbers", destination.substring(3),
                "SignName", config.get("signName"), "TemplateCode", config.get("templateCode"),
                "TemplateParam", json.writeValueAsString(Map.of("code", code))));
        try {
            var signed = smsHeaders(SMS_HOST, "SendSms", SMS_VERSION,
                    Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), UUID.randomUUID().toString());
            String authorization = authorization(config.get("accessKeyId"), provider.secret(), query, signed);
            var builder = HttpRequest.newBuilder(URI.create("https://" + SMS_HOST + "/?" + query))
                    .timeout(REQUEST_TIMEOUT).header("Accept", "application/json")
                    .header("Authorization", authorization).POST(HttpRequest.BodyPublishers.noBody());
            // Host 由 JDK 根据固定请求地址设置，其值已纳入签名。
            signed.forEach((name, value) -> { if (!"host".equals(name)) builder.header(name, value); });
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || !"OK".equals(json.readTree(response.body()).path("Code").asString())) {
                throw deliveryFailure();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw deliveryFailure();
        } catch (IOException | GeneralSecurityException | JacksonException failure) {
            throw deliveryFailure();
        }
    }

    private void sendEmail(LoginSettingsService.Provider provider, String destination, String code) {
        var config = provider.config();
        String host = config.get("host");
        if (!settings.smtpAllowed(host) || destination == null || destination.length() > 254
                || !LoginSettingsService.validAddress(destination)) throw deliveryFailure();
        int port = Integer.parseInt(config.get("port"));
        boolean implicitTls = "tls".equals(config.get("security"));
        var properties = new Properties();
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.connectiontimeout", SMTP_TIMEOUT);
        properties.setProperty("mail.smtp.timeout", SMTP_TIMEOUT);
        properties.setProperty("mail.smtp.writetimeout", SMTP_TIMEOUT);
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(implicitTls));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(!implicitTls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(!implicitTls));
        var session = Session.getInstance(properties);
        try (var transport = session.getTransport("smtp")) {
            var message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.get("from"), true));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(destination, true));
            message.setSubject("进销存管理验证码", StandardCharsets.UTF_8.name());
            message.setText("您的验证码为 " + code + "。请勿向他人透露验证码。", StandardCharsets.UTF_8.name());
            message.saveChanges();
            transport.connect(host, port, config.get("username"), provider.secret());
            transport.sendMessage(message, message.getAllRecipients());
        } catch (MessagingException failure) {
            throw deliveryFailure();
        }
    }

    static Map<String, String> smsHeaders(String host, String action, String version, String date, String nonce)
            throws GeneralSecurityException {
        return new TreeMap<>(Map.of("host", host, "x-acs-action", action, "x-acs-version", version,
                "x-acs-date", date, "x-acs-signature-nonce", nonce, "x-acs-content-sha256", sha256("")));
    }

    static String authorization(String accessKeyId, String secret, String query, Map<String, String> headers)
            throws GeneralSecurityException {
        String signedNames = String.join(";", headers.keySet());
        String canonical = headers.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().strip() + "\n").collect(Collectors.joining());
        String request = "POST\n/\n" + query + "\n" + canonical + "\n" + signedNames + "\n"
                + headers.get("x-acs-content-sha256");
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = mac.doFinal((SIGN_ALGORITHM + "\n" + sha256(request)).getBytes(StandardCharsets.UTF_8));
        return SIGN_ALGORITHM + " Credential=" + accessKeyId + ",SignedHeaders=" + signedNames
                + ",Signature=" + HexFormat.of().formatHex(signature);
    }

    static String query(Map<String, String> fields) {
        return new TreeMap<>(fields).entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("*", "%2A").replace("%7E", "~");
    }

    private static String sha256(String value) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private ApiException deliveryFailure() {
        // 渠道错误可能附带地址、验证码和鉴权标头，只给调用方统一的错误。
        return new ApiException(503, "LOGIN_CODE_DELIVERY_FAILED", "验证码暂时无法发送，请稍后重试");
    }
}
