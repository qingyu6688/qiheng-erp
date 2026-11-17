package cn.erp.documents;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import cn.erp.shared.ApiException;

/** 对象仓只接收服务端生成的键，业务权限由调用模块逐次校验。 */
@Component
public class ObjectFiles {
    private final Path root;
    private final S3Client s3;
    private final String bucket;

    public ObjectFiles(Environment environment) {
        String endpoint = value(environment, "endpoint", "STORAGE_ENDPOINT");
        bucket = value(environment, "bucket", "STORAGE_BUCKET");
        String accessKey = value(environment, "access-key", "STORAGE_ACCESS_KEY");
        String secretKey = value(environment, "secret-key", "STORAGE_SECRET_KEY");
        boolean configured = !endpoint.isBlank() || !bucket.isBlank() || !accessKey.isBlank() || !secretKey.isBlank();
        if (configured) {
            if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
                throw new IllegalStateException("共享对象仓配置不完整，请设置地址、桶名和访问凭据");
            }
            URI uri = URI.create(endpoint);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new IllegalStateException("对象仓地址必须使用HTTP或HTTPS");
            }
            s3 = S3Client.builder().endpointOverride(uri).forcePathStyle(true)
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .serviceConfiguration(configuration -> configuration.chunkedEncodingEnabled(false))
                    .region(Region.of(environment.getProperty("erp.storage.region", "us-east-1")))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            root = null;
        } else {
            boolean localProfile = Arrays.stream(environment.getActiveProfiles())
                    .anyMatch(profile -> profile.equals("dev") || profile.equals("test") || profile.equals("e2e")
                            || profile.equals("upgrade") && environment.getProperty("erp.upgrade.cli", Boolean.class, false))
                    && Arrays.stream(environment.getActiveProfiles()).noneMatch(profile -> profile.equals("prod") || profile.equals("production"));
            if (!localProfile) {
                throw new IllegalStateException("生产环境必须显式配置共享对象仓，禁止使用本机私有文件目录");
            }
            s3 = null;
            root = Path.of(environment.getProperty("erp.storage.local-root",
                    Path.of(System.getProperty("user.home"), ".erp", "private-objects").toString()))
                    .toAbsolutePath().normalize();
            try {
                Files.createDirectories(root);
            } catch (IOException error) {
                throw new IllegalStateException("无法创建私有对象仓目录", error);
            }
        }
    }

    public String put(String key, byte[] bytes, String contentType) {
        validateKey(key);
        try {
            if (s3 != null) {
                s3.putObject(request -> request.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
            } else {
                Path target = localPath(key);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            return key;
        } catch (RuntimeException | IOException error) {
            throw new ApiException(503, "OBJECT_STORAGE_UNAVAILABLE", "文件保存失败，请稍后重试");
        }
    }

    public byte[] read(String key) {
        validateKey(key);
        try {
            return s3 == null ? Files.readAllBytes(localPath(key))
                    : s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
        } catch (RuntimeException | IOException error) {
            throw new ApiException(503, "OBJECT_STORAGE_UNAVAILABLE", "文件暂时无法读取，请稍后重试");
        }
    }

    public void delete(String key) {
        validateKey(key);
        try {
            if (s3 == null) {
                Files.deleteIfExists(localPath(key));
            } else {
                s3.deleteObject(request -> request.bucket(bucket).key(key));
            }
        } catch (RuntimeException | IOException error) {
            throw new ApiException(503, "OBJECT_STORAGE_UNAVAILABLE", "文件清理失败，请稍后重试");
        }
    }

    private Path localPath(String key) throws IOException {
        Path result = root.resolve(key).normalize();
        if (!result.startsWith(root)) {
            throw new IOException("对象键超出私有目录");
        }
        Path current = root;
        for (Path segment : root.relativize(result)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("私有对象仓不允许符号链接");
            }
        }
        return result;
    }

    private static String value(Environment environment, String property, String variable) {
        return environment.getProperty("erp.storage." + property, environment.getProperty(variable, "")).trim();
    }

    private static void validateKey(String key) {
        if (key == null || key.length() > 255 || !key.matches("[A-Za-z0-9][A-Za-z0-9/_-]*") || key.contains("//") || key.endsWith("/")) {
            throw new ApiException(400, "INVALID_OBJECT_KEY", "文件对象标识无效");
        }
    }

    @PreDestroy
    public void close() {
        if (s3 != null) {
            s3.close();
        }
    }
}
