package com.example.bff.config;

import com.example.bff.session.BffSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Provides the {@link RedisTemplate} used to store {@link BffSession} records
 * (see {@code SessionRedisService}, which owns the {@code bff:session:} key prefix).
 * <p>
 * Values are serialized as JSON via Spring Data Redis 4's Jackson-3-backed
 * {@link JacksonJsonRedisSerializer}, typed to {@link BffSession}, instead of the
 * {@code JdkSerializationRedisSerializer} this template used to fall back to. That default only
 * worked because {@code OAuth2AuthorizedClient} happened to be {@code Serializable}; now that
 * sessions are a plain record, there is no reason to keep a Java deserialization surface open on
 * values read back from Redis (see {@link BffSession}'s Javadoc for the full rationale).
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, BffSession> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, BffSession> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JacksonJsonRedisSerializer<>(BffSession.class));
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new JacksonJsonRedisSerializer<>(BffSession.class));
        template.afterPropertiesSet();
        return template;
    }
}
