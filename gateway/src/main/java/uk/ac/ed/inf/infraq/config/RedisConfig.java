package uk.ac.ed.inf.infraq.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Configuration
public class RedisConfig {

    private final String host;
    private final int port;

    public RedisConfig(@Value("${redis.host}") String host,
                       @Value("${redis.port}") int port) {
        this.host = host.trim();
        this.port = port;
    }

    @Bean
    public JedisPool jedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        return new JedisPool(poolConfig, host, port);
    }
}
