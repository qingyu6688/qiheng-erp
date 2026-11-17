package cn.erp.realtime;

import cn.erp.identity.SessionAuthority;
import cn.erp.shared.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import tools.jackson.databind.ObjectMapper;

/** Redis 负责跨节点广播；推送只含变更摘要，业务内容由客户端重新经过权限接口读取。 */
@Component
public class RealtimeHub extends TextWebSocketHandler {
    private static final Logger LOG=LoggerFactory.getLogger(RealtimeHub.class);
    private static final int MAX_CONNECTIONS=1000,MAX_USER_CONNECTIONS=4;
    private static final Set<String> DATA_KEYS=Set.of("session","category","key","revision","messageId");
    private final Map<String,Connection> connections=new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;private final ObjectMapper json;private final SessionAuthority authority;private final ApplicationEventPublisher events;
    private final String topic;
    public RealtimeHub(StringRedisTemplate redis,ObjectMapper json,SessionAuthority authority,ApplicationEventPublisher events,@Value("${spring.session.redis.namespace:erp:session:prod}") String namespace) {this.redis=redis;this.json=json;this.authority=authority;this.events=events;topic=namespace+":events:";}
    public String topicPattern() {return topic+"*";}
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    public void broadcast(UserSignal signal) {
        if(signal.type().length()>64||!signal.data().keySet().stream().allMatch(DATA_KEYS::contains)) {LOG.warn("实时事件包含未允许的字段，类型 {}",signal.type());return;}
        try {redis.convertAndSend(topic+signal.tenantId(),json.writeValueAsString(signal));}
        catch(RuntimeException failure) {LOG.warn("实时摘要广播暂不可用，客户端重连后重新读取消息，异常类型 {}",failure.getClass().getSimpleName());}
    }
    public void receive(Message message,byte[] pattern) {
        try {
            var signal=json.readValue(message.getBody(),UserSignal.class);
            if(!(topic+signal.tenantId()).equals(new String(message.getChannel(),StandardCharsets.UTF_8))||!signal.data().keySet().stream().allMatch(DATA_KEYS::contains)) return;
            events.publishEvent(new RemoteUserSignal(signal));
            boolean kicked="session.kicked".equals(signal.type());
            boolean rotated="session.rotated".equals(signal.type());
            for(var connection:connections.values()) if(connection.identity.tenantId()==signal.tenantId()&&(signal.userId()==0||signal.userId()==connection.identity.userId())) {
                if (!kicked && !rotated) {
                    send(connection,json.writeValueAsString(signal));
                } else if (SessionAuthority.publicId(connection.identity.sessionId()).equals(signal.data().get("session"))) {
                    if (kicked) send(connection,"{\"type\":\"session.kicked\"}");
                    // 主动换发旧连接安静关闭，浏览器会用新 Cookie 重连。
                    close(connection,kicked?CloseStatus.POLICY_VIOLATION:CloseStatus.NORMAL);
                }
            }
        } catch(RuntimeException failure) {LOG.warn("实时事件处理失败，异常类型 {}",failure.getClass().getSimpleName());}
    }
    @Override public synchronized void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        var identity=(SessionAuthority.SocketIdentity)socket.getAttributes().get("identity");
        if(identity==null||connections.size()>=MAX_CONNECTIONS||connections.values().stream().filter(c->c.identity.tenantId()==identity.tenantId()&&c.identity.userId()==identity.userId()).count()>=MAX_USER_CONNECTIONS) {socket.close(CloseStatus.POLICY_VIOLATION);return;}
        socket.setTextMessageSizeLimit(1024);socket.setBinaryMessageSizeLimit(0);
        var connection=new Connection(new ConcurrentWebSocketSessionDecorator(socket,5000,65536),identity);connections.put(socket.getId(),connection);send(connection,"{\"type\":\"connected\"}");
    }
    @Override protected void handleTextMessage(WebSocketSession socket,TextMessage message) {
        var connection=connections.get(socket.getId());if(connection==null) return;
        if(!Set.of("ping","pong","{\"type\":\"ping\"}","{\"type\":\"pong\"}").contains(message.getPayload())) {close(connection,CloseStatus.POLICY_VIOLATION);return;}
        connection.lastSeen=System.currentTimeMillis();if(message.getPayload().contains("ping")) send(connection,"{\"type\":\"pong\"}");
    }
    @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status) {connections.remove(socket.getId());}
    @Override public void handleTransportError(WebSocketSession socket,Throwable failure) {var connection=connections.get(socket.getId());if(connection!=null) close(connection,CloseStatus.SERVER_ERROR);}
    @Scheduled(fixedDelay=30000) public void heartbeat() {
        for(var connection:connections.values()) {
            try {
                var identity=connection.identity;
                if(System.currentTimeMillis()-connection.lastSeen>90000||!authority.valid(identity.sessionId(),identity.tenantId(),identity.userId(),identity.revision())) {close(connection,CloseStatus.POLICY_VIOLATION);continue;}
                send(connection,"{\"type\":\"ping\"}");
            } catch(RuntimeException failure) {LOG.warn("实时会话复核失败，异常类型 {}",failure.getClass().getSimpleName());close(connection,CloseStatus.SERVER_ERROR);}
        }
    }
    private void send(Connection connection,String value) {try {connection.socket.sendMessage(new TextMessage(value));} catch(IOException|RuntimeException failure) {close(connection,CloseStatus.SESSION_NOT_RELIABLE);}}
    private void close(Connection connection,CloseStatus reason) {connections.remove(connection.socket.getId());try {connection.socket.close(reason);} catch(IOException failure) {LOG.debug("实时连接已断开");}}
    private static class Connection {
        final WebSocketSession socket;final SessionAuthority.SocketIdentity identity;volatile long lastSeen=System.currentTimeMillis();
        Connection(WebSocketSession socket,SessionAuthority.SocketIdentity identity) {this.socket=socket;this.identity=identity;}
    }
}
