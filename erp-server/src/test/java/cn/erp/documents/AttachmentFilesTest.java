package cn.erp.documents;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import cn.erp.shared.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentFilesTest {
    private final AttachmentFiles files = new AttachmentFiles();

    @Test void acceptsActualHeadersAndIgnoresClaimedMime() throws Exception {
        var pdf = files.validate(file("单据.PDF", "text/html", "%PDF-1.7\n测试\n%%EOF".getBytes(StandardCharsets.UTF_8)));
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        assertThat(pdf.sha256()).hasSize(64);
        var csv = files.validate(file("商品.csv", "application/octet-stream", "编码,名称\nA,商品".getBytes(StandardCharsets.UTF_8)));
        assertThat(csv.contentType()).startsWith("text/csv");
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String name : java.util.List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml")) {
                zip.putNextEntry(new ZipEntry(name)); zip.write("<xml/>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        assertThat(files.validate(file("表.xlsx", "text/plain", bytes.toByteArray())).contentType()).contains("spreadsheetml");
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (String format : java.util.List.of("png", "jpg")) {
            bytes.reset(); javax.imageio.ImageIO.write(image, format, bytes);
            assertThat(files.validate(file("照片." + format, "text/plain", bytes.toByteArray())).contentType()).startsWith("image/");
        }
    }

    @Test void rejectsDisguisedFilesPathNamesEmptyAndOversize() {
        for (String name : java.util.List.of("测试.exe", "../测试.csv", "C:\\测试.csv", "测试\n.csv", "测试.pdf", "测试.png", "测试.jpg", "测试.xlsx")) {
            assertThatThrownBy(() -> files.validate(file(name, "application/pdf", "伪装内容".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> files.validate(file("测试.csv", "text/csv", new byte[]{(byte)0xff,0}))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validate(file("测试.csv", "text/csv", new byte[0]))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validate(file("测试.csv", "text/csv", new byte[AttachmentFiles.MAX_BYTES + 1])))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(413));
    }

    private MockMultipartFile file(String name, String mime, byte[] bytes) { return new MockMultipartFile("file", name, mime, bytes); }
}
