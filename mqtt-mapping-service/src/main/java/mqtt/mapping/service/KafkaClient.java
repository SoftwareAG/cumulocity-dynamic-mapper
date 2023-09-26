package mqtt.mapping.service;

import java.util.Properties;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class KafkaClient {

	public void test() {
		
		KafkaConsumer<Object, Object> kafkaConsumer = new KafkaConsumer<>(new Properties());
		KafkaProducer<Object, Object> kafkaProducer = new KafkaProducer<>(new Properties());
		
	}
	
}
