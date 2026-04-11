package uk.ac.ed.inf.infraq.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Publishes inference request messages to RabbitMQ.
 * Adapted from CW1 RabbitMQService, simplified to publish-only.
 */
@Service
public class RabbitMQPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQPublisher.class);
    public static final String QUEUE_NAME = "inference.requests";

    private final ConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    public RabbitMQPublisher(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        initQueue();
    }

    private void initQueue() {
        try (Connection conn = connectionFactory.newConnection();
             Channel channel = conn.createChannel()) {
            // Durable queue, survives broker restart
            channel.queueDeclare(QUEUE_NAME, true, false, false, null);
            log.info("RabbitMQ queue '{}' declared", QUEUE_NAME);
        } catch (Exception e) {
            log.warn("Could not declare queue on startup (broker may not be ready): {}", e.getMessage());
        }
    }

    public void publish(Map<String, Object> message) {
        try (Connection conn = connectionFactory.newConnection();
             Channel channel = conn.createChannel()) {
            channel.queueDeclare(QUEUE_NAME, true, false, false, null);
            byte[] body = objectMapper.writeValueAsBytes(message);
            channel.basicPublish("", QUEUE_NAME, MessageProperties.PERSISTENT_TEXT_PLAIN, body);
        } catch (Exception e) {
            log.error("Failed to publish to RabbitMQ: {}", e.getMessage());
            throw new RuntimeException("RabbitMQ publish failed", e);
        }
    }

    public long getQueueDepth() {
        try (Connection conn = connectionFactory.newConnection();
             Channel channel = conn.createChannel()) {
            return channel.messageCount(QUEUE_NAME);
        } catch (Exception e) {
            return -1;
        }
    }
}
