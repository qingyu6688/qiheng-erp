package cn.erp.ai.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import cn.erp.ai.common.AiException;
import tools.jackson.databind.JsonNode;

@Component
final class KnowledgeParser {
    static final int MAX_BYTES = 20 * 1024 * 1024;
    static final int MAX_SEGMENT = 800;
    private static final int MAX_TEXT = 500_000;
    private static final int MAX_IMAGE_PIXELS = 25_000_000;
    private static final int MAX_ZIP_EXPANDED = 64 * 1024 * 1024;
    private static final int MIN_SEGMENT = 300;
    private static final int OVERLAP = 50;
    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern SAFE_FILENAME = Pattern.compile("[^/\\\\\\p{Cntrl}]{1,200}");
    private final KnowledgeHttp http;
    private final String ocrUrl;
    private final String ocrAuthorization;

    KnowledgeParser(KnowledgeHttp http,
                    @Value("${erp.ai.ocr-url:${ERP_AI_OCR_URL:}}") String ocrUrl,
                    @Value("${erp.ai.ocr-token:${ERP_AI_OCR_TOKEN:}}") String ocrToken) {
        this.http = http;
        this.ocrUrl = ocrUrl;
        this.ocrAuthorization = KnowledgeHttp.bearer(ocrToken);
    }

    FileData validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("请选择非空文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw tooLarge();
        }
        try {
            return validate(file.getOriginalFilename(), file.getBytes());
        } catch (IOException error) {
            throw invalid("文件内容无法读取，请检查文件是否完整");
        }
    }

    FileData validate(String name, byte[] bytes) {
        if (name == null || !SAFE_FILENAME.matcher(name).matches() || name.lastIndexOf('.') <= 0) {
            throw invalid("请使用有效的文件名和扩展名");
        }
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }
        try {
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            String mime = switch (extension) {
                case "md", "txt", "csv" -> {
                    utf8(bytes);
                    yield switch (extension) {
                        case "md" -> "text/markdown";
                        case "csv" -> "text/csv";
                        default -> "text/plain";
                    };
                }
                case "pdf" -> { pdfSignature(bytes); yield PDF; }
                case "jpg", "jpeg" -> { image(bytes, "JPEG"); yield "image/jpeg"; }
                case "png" -> { image(bytes, "PNG"); yield "image/png"; }
                case "docx" -> { office(bytes, "word/document.xml"); yield DOCX; }
                case "xlsx" -> { office(bytes, "xl/workbook.xml"); yield XLSX; }
                default -> throw invalid("知识库支持 Markdown、TXT、CSV、PDF、DOCX、XLSX、JPG 和 PNG");
            };
            return new FileData(name, mime, bytes, digest(bytes));
        } catch (IOException error) {
            throw invalid("文件内容与扩展名不符或文件已损坏");
        }
    }

    List<Part> parse(byte[] bytes, String mime) {
        try {
            List<TextPage> pages = extract(bytes, mime);
            long count = pages.stream().mapToLong(page -> page.text().length()).sum();
            if (count > MAX_TEXT) {
                throw invalid("提取文本超过上限，请将文件拆分后上传");
            }
            List<Part> parts = new ArrayList<>();
            List<String> headings = new ArrayList<>();
            for (TextPage page : pages) {
                splitPage(page, headings, parts);
            }
            if (parts.isEmpty()) {
                throw invalid("文件中没有可检索文本");
            }
            return List.copyOf(parts);
        } catch (IOException | SAXException | TikaException error) {
            throw invalid("无法解析文件，请检查文件是否加密或损坏");
        }
    }

    private List<TextPage> extract(byte[] bytes, String mime) throws IOException, SAXException, TikaException {
        if (mime.startsWith("text/")) {
            return List.of(new TextPage(null, utf8(bytes)));
        }
        if (mime.startsWith("image/")) {
            return ocr(bytes, false);
        }
        var handler = new TextHandler(PDF.equals(mime));
        var metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, mime);
        var context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override public boolean shouldParseEmbedded(Metadata metadata) { return false; }
            @Override public void parseEmbedded(java.io.InputStream stream, org.xml.sax.ContentHandler content,
                                                Metadata metadata, boolean outputHtml) {}
        });
        var pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdfConfig.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdfConfig);
        var parser = PDF.equals(mime) ? new PDFParser() : new OOXMLParser();
        try (var input = new ByteArrayInputStream(bytes)) {
            parser.parse(input, handler, metadata, context);
        }
        List<TextPage> pages = handler.pages();
        if (PDF.equals(mime) && (pages.isEmpty() || pages.stream().anyMatch(item -> item.text().isBlank()))) {
            List<TextPage> recognized = ocr(bytes, true);
            if (pages.size() > recognized.size()) {
                throw invalid("OCR 返回的页数不足，请拆分扫描件后重试");
            }
            var joined = new ArrayList<>(recognized);
            for (int i = 0; i < pages.size(); i++) {
                if (!pages.get(i).text().isBlank()) {
                    joined.set(i, pages.get(i));
                }
            }
            return joined;
        }
        return pages;
    }

    private List<TextPage> ocr(byte[] bytes, boolean pdf) {
        if (ocrUrl.isBlank()) {
            throw invalid("扫描件或图片需要先配置私有 OCR 服务");
        }
        var response = http.send(ocrUrl, "POST", ocrAuthorization, Map.of(
                "file", Base64.getEncoder().encodeToString(bytes), "fileType", pdf ? 0 : 1, "visualize", false), 120);
        if (!response.ok() || response.data() == null || response.data().path("errorCode").asInt(-1) != 0) {
            throw KnowledgeHttp.unavailable();
        }
        var results = response.data().path("result").path("ocrResults");
        if (!results.isArray() || results.isEmpty()) {
            throw invalid("OCR 未返回可识别的页面");
        }
        List<TextPage> pages = new ArrayList<>();
        for (JsonNode page : results) {
            var text = new StringBuilder();
            for (JsonNode row : page.path("prunedResult").path("rec_texts")) {
                if (row.isTextual()) {
                    text.append(row.asString()).append('\n');
                }
            }
            pages.add(new TextPage(pages.size() + 1, text.toString()));
        }
        return pages;
    }

    private void splitPage(TextPage page, List<String> headings, List<Part> parts) {
        var text = new StringBuilder();
        for (String line : page.text().split("\\R")) {
            var matcher = HEADING.matcher(line.strip());
            if (matcher.matches()) {
                splitSection(text.toString(), headingPath(headings), page.page(), parts);
                text.setLength(0);
                int level = matcher.group(1).length();
                while (headings.size() >= level) {
                    headings.removeLast();
                }
                while (headings.size() < level - 1) {
                    headings.add("");
                }
                headings.add(matcher.group(2).strip());
            } else {
                text.append(line).append('\n');
            }
        }
        splitSection(text.toString(), headingPath(headings), page.page(), parts);
    }

    private String headingPath(List<String> headings) {
        String path = String.join(" / ", headings.stream().filter(s -> !s.isBlank()).toList());
        return path.codePointCount(0, path.length()) <= 500 ? path : path.substring(0, path.offsetByCodePoints(0, 500));
    }

    private void splitSection(String original, String heading, Integer page, List<Part> parts) {
        String text = original.strip();
        int length = text.codePointCount(0, text.length());
        int start = 0;
        while (start < length) {
            int end = Math.min(start + MAX_SEGMENT, length);
            if (end < length) {
                if (length - end < MIN_SEGMENT - OVERLAP) {
                    end = length - MIN_SEGMENT + OVERLAP;
                }
                int index = text.offsetByCodePoints(0, end);
                int lower = Math.max(start + MIN_SEGMENT, end - 100);
                for (int i = end; i > lower; i--) {
                    int character = text.codePointBefore(index);
                    if ("。！？；!?;\n".indexOf(character) >= 0) {
                        end = i;
                        break;
                    }
                    index -= Character.charCount(character);
                }
            }
            String chunk = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end)).strip();
            if (!chunk.isBlank()) {
                parts.add(new Part(heading, page, page, chunk));
            }
            if (end == length) {
                break;
            }
            start = end - OVERLAP;
        }
    }

    private String utf8(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.isBlank() || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw invalid("文本文件必须包含有效的 UTF-8 文字");
        }
        return text;
    }

    private void pdfSignature(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(8, bytes.length), StandardCharsets.US_ASCII);
        int start = Math.max(0, bytes.length - 1024);
        String tail = new String(bytes, start, bytes.length - start, StandardCharsets.US_ASCII);
        if (!head.matches("%PDF-[12]\\.[0-9]") || !tail.contains("%%EOF")) {
            throw invalid("PDF 文件头或结束标记无效");
        }
    }

    private void image(byte[] bytes, String format) throws IOException {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw invalid("图片格式无效");
            }
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if (!format.equalsIgnoreCase(reader.getFormatName())
                        || (long) reader.getWidth(0) * reader.getHeight(0) > MAX_IMAGE_PIXELS
                        || reader.read(0) == null) {
                    throw invalid("图片格式与扩展名不符或尺寸过大");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private void office(byte[] bytes, String expected) throws IOException {
        var entries = new HashSet<String>();
        int expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (!entries.add(name) || entries.size() > 1000 || name.contains("..") || name.startsWith("/")
                        || name.contains("\\") || name.toLowerCase(Locale.ROOT).contains("vbaproject")) {
                    throw invalid("Office 文件包含不允许的内容");
                }
                for (int read; (read = zip.read(buffer)) != -1;) {
                    expanded += read;
                    if (expanded > MAX_ZIP_EXPANDED) {
                        throw invalid("Office 文件解压内容过大");
                    }
                }
            }
        }
        if (!entries.containsAll(Set.of("[Content_Types].xml", "_rels/.rels", expected))) {
            throw invalid("Office 文件类型与扩展名不符");
        }
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少摘要算法", error);
        }
    }

    private static AiException invalid(String message) {
        return new AiException(400, "INVALID_KNOWLEDGE_FILE", message);
    }

    private static AiException tooLarge() {
        return new AiException(413, "KNOWLEDGE_FILE_TOO_LARGE", "单个知识库文件不能超过 20 MB");
    }

    record FileData(String fileName, String mime, byte[] bytes, String hash) {}
    record Part(String heading, Integer pageFrom, Integer pageTo, String content) {}
    private record TextPage(Integer page, String text) {}

    private static final class TextHandler extends DefaultHandler {
        private final boolean pdf;
        private final List<TextPage> pages = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private int pageDepth;
        private int count;

        TextHandler(boolean pdf) {
            this.pdf = pdf;
        }

        List<TextPage> pages() {
            if (!pdf || (pages.isEmpty() && !text.isEmpty())) {
                return List.of(new TextPage(null, text.toString()));
            }
            return List.copyOf(pages);
        }

        @Override
        public void startElement(String uri, String local, String qualified, Attributes attributes) {
            if (pdf && "div".equals(local)) {
                if ("page".equals(attributes.getValue("class"))) {
                    text.setLength(0);
                    pageDepth = 1;
                } else if (pageDepth > 0) {
                    pageDepth++;
                }
            }
            if (local.matches("h[1-6]")) {
                text.append("#".repeat(local.charAt(1) - '0')).append(' ');
            }
        }

        @Override
        public void characters(char[] chars, int start, int length) throws SAXException {
            count += length;
            if (count > MAX_TEXT) {
                throw new SAXException("提取文本超过上限");
            }
            text.append(chars, start, length);
        }

        @Override
        public void endElement(String uri, String local, String qualified) {
            if (pdf && "div".equals(local) && pageDepth > 0 && --pageDepth == 0) {
                pages.add(new TextPage(pages.size() + 1, text.toString()));
                text.setLength(0);
            }
            if (Set.of("p", "li", "tr", "br", "h1", "h2", "h3", "h4", "h5", "h6").contains(local)) {
                text.append('\n');
            } else if ("td".equals(local)) {
                text.append('\t');
            }
        }
    }
}
