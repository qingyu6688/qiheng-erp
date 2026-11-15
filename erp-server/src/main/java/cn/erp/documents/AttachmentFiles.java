package cn.erp.documents;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import cn.erp.shared.ApiException;

@Component
public class AttachmentFiles {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 25_000_000L;
    private static final int MAX_ZIP_BYTES = 64 * 1024 * 1024;

    public Validated validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("请选择非空文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(413, "FILE_TOO_LARGE", "单个附件不能超过20MB");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 200 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(value -> value < 32 || value == 127)) {
            throw invalid("文件名无效，请使用不含路径的普通文件名");
        }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_BYTES) {
                throw new ApiException(413, "FILE_TOO_LARGE", "单个附件不能超过20MB");
            }
            String contentType = switch (extension) {
                case "pdf" -> { pdf(bytes); yield "application/pdf"; }
                case "jpg", "jpeg" -> { image(bytes, false); yield "image/jpeg"; }
                case "png" -> { image(bytes, true); yield "image/png"; }
                case "xlsx" -> { xlsx(bytes); yield "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
                case "csv" -> { csv(bytes); yield "text/csv;charset=UTF-8"; }
                default -> throw invalid("仅支持PDF、JPG、PNG、XLSX和CSV文件");
            };
            return new Validated(name, contentType, bytes, digest(bytes));
        } catch (IOException error) {
            throw invalid("文件内容无法读取，请检查文件是否完整");
        }
    }

    private void pdf(byte[] bytes) {
        String beginning = new String(bytes, 0, Math.min(bytes.length, 8), StandardCharsets.US_ASCII);
        String ending = new String(bytes, Math.max(0, bytes.length - 1024), Math.min(bytes.length, 1024), StandardCharsets.US_ASCII);
        if (!beginning.matches("%PDF-[12]\\.[0-9].*") || !ending.contains("%%EOF")) {
            throw invalid("PDF文件头或结束标记无效");
        }
    }

    private void image(byte[] bytes, boolean png) throws IOException {
        byte[] signature = png ? new byte[]{(byte)137,80,78,71,13,10,26,10} : new byte[]{(byte)255,(byte)216,(byte)255};
        if (bytes.length < signature.length || !java.util.Arrays.equals(signature, java.util.Arrays.copyOf(bytes, signature.length))) {
            throw invalid("图片实际内容与扩展名不符");
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) { throw invalid("图片格式无效"); }
            var reader = readers.next();
            try {
                reader.setInput(input);
                long width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width * height > MAX_IMAGE_PIXELS) { throw invalid("图片尺寸过大或无效"); }
                reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private void xlsx(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw invalid("XLSX文件实际内容无效");
        }
        var names = new java.util.HashSet<String>();
        int expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!names.add(entry.getName()) || names.size() > 1000 || entry.getName().contains("..")
                        || entry.getName().startsWith("/") || entry.getName().contains("\\")
                        || entry.getName().toLowerCase(Locale.ROOT).contains("vbaproject")) {
                    throw invalid("XLSX文件包含不允许的内容");
                }
                byte[] buffer = new byte[8192];
                for (int count; (count = zip.read(buffer)) != -1;) {
                    expanded += count;
                    if (expanded > MAX_ZIP_BYTES) { throw invalid("XLSX解压内容过大"); }
                }
            }
        }
        if (!names.containsAll(Set.of("[Content_Types].xml", "xl/workbook.xml", "_rels/.rels"))) {
            throw invalid("文件不是完整的XLSX工作簿");
        }
    }

    private void csv(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isBlank() || text.chars().anyMatch(value -> value < 32 && value != '\r' && value != '\n' && value != '\t')) {
                throw invalid("CSV必须是有效UTF-8文本，不能包含二进制内容");
            }
        } catch (CharacterCodingException error) {
            throw invalid("CSV必须使用UTF-8编码");
        }
    }

    private String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少摘要算法", error);
        }
    }

    private ApiException invalid(String message) { return new ApiException(400, "INVALID_FILE", message); }

    public record Validated(String fileName, String contentType, byte[] bytes, String sha256) {}
}
