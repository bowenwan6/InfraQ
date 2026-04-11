package uk.ac.ed.inf.infraq.service;

import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Set;

/**
 * Redis service adapted from CW1, extended with incr/setex/expire for InfraQ.
 */
@Service
public class RedisService {

    private final JedisPool jedisPool;

    public RedisService(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public String get(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        }
    }

    public String getOrDefault(String key, String defaultValue) {
        String val = get(key);
        return val != null ? val : defaultValue;
    }

    public void set(String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(key, value);
        }
    }

    public void setex(String key, long seconds, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, seconds, value);
        }
    }

    public long incr(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.incr(key);
        }
    }

    public void hset(String hash, String field, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(hash, field, value);
        }
    }

    public String hget(String hash, String field) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hget(hash, field);
        }
    }

    public Map<String, String> hgetAll(String hash) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hgetAll(hash);
        }
    }

    public void del(String... keys) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(keys);
        }
    }

    public Set<String> keys(String pattern) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.keys(pattern);
        }
    }

    public boolean exists(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists(key);
        }
    }
}
