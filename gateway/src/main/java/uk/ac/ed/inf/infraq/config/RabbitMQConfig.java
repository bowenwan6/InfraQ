package uk.ac.ed.inf.infraq.config;

import com.rabbitmq.client.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    private final String host;
    private final int port;

    public RabbitMQConfig(@Value("${rabbitmq.host}") String host,
                          @Value("${rabbitmq.port}") int port) {
        this.host = host.trim();
        this.port = port;
    }

    @Bean
    public ConnectionFactory rabbitConnectionFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        return factory;
    }
}
