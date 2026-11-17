package cn.erp.openapi;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class OpenWebhookTransport {
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private final Set<String> hosts;

    OpenWebhookTransport(@Value("${ERP_OPEN_WEBHOOK_HOSTS:}") String hosts) {
        this.hosts=Arrays.stream(hosts.split(",")).map(value -> value.strip().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    URI validate(String url) {
        if (url==null || url.length()>500 || url.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        URI uri;
        try { uri=URI.create(url); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        String host=uri.getHost();
        if (!"https".equals(uri.getScheme()) || host==null || host.indexOf('.')<0 || host.endsWith(".")
                || !host.matches("[a-zA-Z0-9.-]+") || host.matches("[0-9.]+") || uri.getRawUserInfo()!=null
                || uri.getRawFragment()!=null || (uri.getPort()!=-1 && (uri.getPort()<1 || uri.getPort()>65535))
                || !hosts.contains(hostAndPort(uri))) throw invalid();
        return URI.create(uri.toASCIIString());
    }

    int send(String url,String eventId,String timestamp,String signature,byte[] body) throws IOException {
        URI uri=validate(url);
        InetAddress ip=resolve(uri.getHost());
        int port=uri.getPort()==-1?443:uri.getPort();
        try (var stream=new Socket()) {
            // 固定连接到刚验证的公网地址，TLS 仍校验证书中的原始主机，防止二次 DNS 查询造成域名重绑定。
            stream.connect(new InetSocketAddress(ip,port),CONNECT_TIMEOUT_MS);
            stream.setSoTimeout(READ_TIMEOUT_MS);
            var factory=(SSLSocketFactory)SSLSocketFactory.getDefault();
            try (var tls=(SSLSocket)factory.createSocket(stream,uri.getHost(),port,true)) {
                SSLParameters parameters=tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                var output=tls.getOutputStream();
                output.write(headers(uri,eventId,timestamp,signature,body.length).getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();
                return status(tls.getInputStream());
            }
        }
    }

    private InetAddress resolve(String host) throws UnknownHostException {
        var addresses=List.of(InetAddress.getAllByName(host));
        if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublic(address))) throw new UnknownHostException();
        return addresses.getFirst();
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes=address.getAddress();
        int first=Byte.toUnsignedInt(bytes[0]);
        int second=Byte.toUnsignedInt(bytes[1]);
        if (bytes.length==16) {
            // 仅允许全球单播，并拒绝文档、隧道和保留前缀。
            return (first & 0xe0)==0x20 && first!=0x3f && !(first==0x20 && second==0x02)
                    && !(first==0x20 && second==1 && Byte.toUnsignedInt(bytes[2])<=0x01)
                    && !(first==0x20 && second==1 && bytes[2]==0x0d && bytes[3]==(byte)0xb8);
        }
        int third=Byte.toUnsignedInt(bytes[2]);
        return first!=0 && first<224 && !(first==100 && second>=64 && second<=127)
                && !(first==192 && ((second==0 && (third==0 || third==2)) || (second==88 && third==99)))
                && !(first==198 && (second==18 || second==19 || (second==51 && third==100)))
                && !(first==203 && second==0 && third==113);
    }

    private static String hostAndPort(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT)+(uri.getPort()==-1 || uri.getPort()==443 ? "" : ":"+uri.getPort());
    }

    private static String headers(URI uri,String eventId,String timestamp,String signature,int length) {
        String path=uri.getRawPath().isEmpty()?"/":uri.getRawPath();
        String target=path+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
        return "POST "+target+" HTTP/1.1\r\nHost: "+hostAndPort(uri)+"\r\nContent-Type: application/json; charset=utf-8\r\n"
                +"Content-Length: "+length+"\r\nConnection: close\r\nX-Erp-Event-Id: "+eventId+"\r\n"
                +"X-Erp-Timestamp: "+timestamp+"\r\nX-Erp-Signature: "+signature+"\r\n\r\n";
    }

    private static int status(java.io.InputStream input) throws IOException {
        var line=new StringBuilder();
        for (int i=0;i<256;i++) {
            int next=input.read();
            if (next<0 || next=='\n') break;
            line.append((char)next);
        }
        if (!line.toString().matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: [^\\r]*|)\\r")) throw new IOException("推送响应状态无效");
        return Integer.parseInt(line.substring(9,12));
    }

    private static cn.erp.shared.ApiException invalid() {
        return OpenPolicy.invalid("Webhook 地址须为部署允许的 HTTPS 主机");
    }
}
