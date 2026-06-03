package com.interview.redis.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 【面试考点】Redis 配置类 - 序列化配置
 * 
 * 问题：Redis 默认使用 JDK 序列化，存储后是可读性很差的二进制数据
 * 解决：配置 JSON 序列化，提高可读性和跨语言兼容性
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * 【面试考点】RedisTemplate 序列化配置
     * 
     * 为什么要自定义序列化？
     * 1. JDK 序列化：存储二进制数据，可读性差，跨语言困难
     * 2. JSON 序列化：可读性好，跨语言兼容
     * 
     * 【面试追问】如何选择序列化方式？
     * - StringRedisTemplate：只支持字符串，简单场景用这个
     * - Jackson2JsonRedisSerializer：通用 JSON 序列化，推荐使用
     * - KryoSerializer：性能更好，但需要注册类
     * - FSTSerializer：性能和 Kryo 类似
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        log.info("初始化 RedisTemplate，使用 Jackson2Json 序列化");
        
        // 创建 RedisTemplate
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        
        // 配置 JSON 序列化器
        Jackson2JsonRedisSerializer<Object> jsonSerializer = createJsonSerializer();
        
        // Key 使用 String 序列化
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        
        // Value 使用 JSON 序列化
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 【面试考点】StringRedisTemplate
     * 
     * 适用场景：
     * 1. 简单的 KV 存储
     * 2. 不需要存储复杂对象
     * 3. 与其他语言共用 Redis 时
     * 
     * 【面试追问】StringRedisTemplate vs RedisTemplate 的区别？
     * → 答：StringRedisTemplate 的 Key 和 Value 都是 String 类型
     * → 答：RedisTemplate 可以存储任意 Object，但需要配置序列化器
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private Jackson2JsonRedisSerializer<Object> createJsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        // 设置可见性，让序列化器能访问 private 字段
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 允许序列化任何类型（生产环境建议指定具体类型）
        mapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL
        );
        
        // 使用构造函数注入 ObjectMapper，避免使用已过时的 setObjectMapper 方法
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(mapper, Object.class);
        return serializer;
    }
}