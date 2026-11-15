package cn.erp.shared;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

@Configuration(proxyBeanMethods = false)
class ApiTimeConfiguration implements WebMvcConfigurer {
    private final JsonMapper json;

    ApiTimeConfiguration(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        // JDBC 把 UTC 的 DATETIME 读成不带时区的值。仅在 HTTP 出口补上时区，保留内部快照序列化。
        var utc = new SimpleModule("ERP 接口 UTC 时间");
        utc.addSerializer(LocalDateTime.class, new UtcDateTimeSerializer());
        builder.withJsonConverter(new JacksonJsonHttpMessageConverter(json.rebuild().addModule(utc).build()));
    }

    private static class UtcDateTimeSerializer extends ValueSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(value.toInstant(ZoneOffset.UTC).toString());
        }
    }
}
