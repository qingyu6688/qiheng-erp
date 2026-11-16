package cn.erp.realtime;

import cn.erp.identity.*;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration(proxyBeanMethods=false)
@EnableWebSocket
class RealtimeConfiguration implements WebSocketConfigurer {
    private final RealtimeHub hub;private final AccessService access;private final SessionAuthority authority;private final List<String> origins;
    RealtimeConfiguration(RealtimeHub hub,AccessService access,SessionAuthority authority,@Value("${erp.websocket.allowed-origins:}") String origins) {
        this.hub=hub;this.access=access;this.authority=authority;this.origins=Arrays.stream(origins.split(",")).map(String::strip).filter(v->!v.isEmpty()).toList();
        if(this.origins.stream().anyMatch(v->v.contains("*")||!v.matches("https?://[^/]+"))) throw new IllegalArgumentException("实时推送来源必须为明确的HTTP或HTTPS站点");
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hub,"/ws/v1/events").setAllowedOrigins(origins.toArray(String[]::new)).addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes) {
                if(!(request instanceof ServletServerHttpRequest servlet)) return false;
                String origin=request.getHeaders().getOrigin();URI uri=request.getURI();String same=uri.getScheme()+"://"+uri.getAuthority();
                if(origin==null||!origin.equals(same)&&!RealtimeConfiguration.this.origins.contains(origin)) {response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);return false;}
                access.requireUser(servlet.getServletRequest());attributes.put("identity",authority.socketIdentity(servlet.getServletRequest()));return true;
            }
            @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception error) {}
        });
    }
    @Bean RedisMessageListenerContainer platformEvents(RedisConnectionFactory factory) {
        var container=new RedisMessageListenerContainer();container.setConnectionFactory(factory);container.setAutoStartup(false);container.setMaxSubscriptionRegistrationWaitingTime(2000);container.addMessageListener(hub::receive,new PatternTopic(hub.topicPattern()));return container;
    }
}
