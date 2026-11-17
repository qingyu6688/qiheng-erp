package cn.erp.openapi;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.TreeMap;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

final class OpenRequest extends HttpServletRequestWrapper {
    private static final int MAX_BODY = 512 * 1024;
    private static final int MAX_QUERY = 2048;
    private final byte[] body;

    OpenRequest(HttpServletRequest request) throws IOException {
        super(request);
        if (request.getContentLengthLong() > MAX_BODY) throw OpenPolicy.invalid("请求正文超过 512 KB");
        this.body=request.getInputStream().readNBytes(MAX_BODY+1);
        if (body.length > MAX_BODY || (request.getMethod().equals("GET") && body.length!=0)) {
            throw OpenPolicy.invalid("正文过长或请求方法不支持正文");
        }
    }

    String canonical(String timestamp,String nonce,String company,String idempotency) {
        String method=getMethod();
        String path=getRequestURI();
        if (!path.matches("/open/v1(?:/[a-z0-9_-]+){1,5}") || path.length()>120
                || !path.equals(getServletPath())) throw OpenPolicy.invalid("开放接口路径格式无效");
        return canonical(method,path,getQueryString(),timestamp,nonce,company,idempotency,body);
    }

    static String canonical(String method,String path,String query,String timestamp,String nonce,String company,String idempotency,byte[] body) {
        return String.join("\n",method,path,canonicalQuery(query),timestamp,nonce,company,idempotency,sha256(body));
    }

    static String canonicalQuery(String query) {
        if (query==null || query.isEmpty()) return "";
        if (query.length()>MAX_QUERY) throw OpenPolicy.invalid("查询参数过长");
        var params=new TreeMap<String,String>();
        for (String part:query.split("&",-1)) {
            int split=part.indexOf('=');
            if (split<1) throw OpenPolicy.invalid("查询参数须为键值对");
            String key=decode(part.substring(0,split));
            String value=decode(part.substring(split+1));
            if (!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") || params.put(key,value)!=null) {
                throw OpenPolicy.invalid("查询参数名称无效或重复");
            }
        }
        return params.entrySet().stream().map(entry -> encode(entry.getKey())+"="+encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String decode(String text) {
        try {
            String value=URLDecoder.decode(text,StandardCharsets.UTF_8);
            if (value.indexOf('\uFFFD')>=0 || value.codePoints().anyMatch(Character::isISOControl)) {
                throw OpenPolicy.invalid("查询参数 UTF-8 编码无效");
            }
            return value;
        } catch (IllegalArgumentException invalid) { throw OpenPolicy.invalid("查询参数编码无效"); }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20").replace("*","%2A").replace("%7E","~");
    }

    static String header(HttpServletRequest request,String name) {
        var values=Collections.list(request.getHeaders(name));
        if (values.size()>1) throw OpenPolicy.invalid("请求包含重复的签名头");
        return values.isEmpty()?"":values.getFirst();
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("无法计算请求摘要",failure); }
    }

    @Override
    public ServletInputStream getInputStream() {
        var stream=new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return stream.read(); }
            @Override public int read(byte[] bytes,int offset,int length) { return stream.read(bytes,offset,length); }
            @Override public boolean isFinished() { return stream.available()==0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("开放请求不支持异步读取正文"); }
        };
    }

    @Override
    public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
}
