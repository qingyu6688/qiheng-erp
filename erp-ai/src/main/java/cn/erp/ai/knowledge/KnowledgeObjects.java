package cn.erp.ai.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import cn.erp.ai.common.AiException;

@Component
final class KnowledgeObjects {
    private static final Pattern TENANT = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final Pattern KEY = Pattern.compile("[a-f0-9-]{36}\\.blob");
    private final Path root;

    KnowledgeObjects(@Value("${erp.ai.kb-storage:${ERP_AI_KB_STORAGE:./data/ai-knowledge}}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    String put(String tenant, byte[] bytes) {
        String key = UUID.randomUUID() + ".blob";
        Path target = path(tenant, key);
        try {
            Files.createDirectories(root);
            Files.createDirectories(target.getParent());
            if (Files.isSymbolicLink(root) || Files.isSymbolicLink(target.getParent())) {
                throw storageUnavailable();
            }
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            remove(target);
                        }
                    }
                });
            }
            return key;
        } catch (IOException error) {
            remove(target);
            throw storageUnavailable();
        }
    }

    byte[] read(String tenant, String key) {
        Path target = path(tenant, key);
        try {
            if (Files.isSymbolicLink(root) || Files.isSymbolicLink(target.getParent())
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw storageUnavailable();
            }
            try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(KnowledgeParser.MAX_BYTES + 1);
                if (bytes.length > KnowledgeParser.MAX_BYTES) {
                    throw storageUnavailable();
                }
                return bytes;
            }
        } catch (IOException error) {
            throw storageUnavailable();
        }
    }

    private Path path(String tenant, String key) {
        if (!TENANT.matcher(tenant).matches() || !KEY.matcher(key).matches()) {
            throw new AiException(400, "INVALID_KNOWLEDGE_OBJECT", "知识库文件标识无效");
        }
        return root.resolve(tenant).resolve(key);
    }

    private static AiException storageUnavailable() {
        return new AiException(503, "KNOWLEDGE_STORAGE_UNAVAILABLE", "知识库文件存储暂不可用");
    }

    private void remove(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException error) {
            org.slf4j.LoggerFactory.getLogger(KnowledgeObjects.class).warn("知识库回滚文件清理失败");
        }
    }
}
