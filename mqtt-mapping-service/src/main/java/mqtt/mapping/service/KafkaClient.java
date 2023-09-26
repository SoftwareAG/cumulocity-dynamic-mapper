/*
 * Copyright (c) 2022 Software AG, Darmstadt, Germany and/or Software AG USA Inc., Reston, VA, USA,
 * and/or its subsidiaries and/or its affiliates and/or their licensors.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Christof Strack, Stefan Witschel
 */

package mqtt.mapping.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConfigurationConnection;
import mqtt.mapping.configuration.ConnectionConfigurationComponent;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.core.ServiceStatus;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.inbound.AsynchronousDispatcher;
import mqtt.mapping.processor.model.ProcessingContext;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class KafkaClient {

    private static final int WAIT_PERIOD_MS = 10000;
    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";

    private ConfigurationConnection connectionConfiguration;
    private Certificate cert;


    private ConnectionConfigurationComponent connectionConfigurationComponent;

    @Autowired
    public void setConnectionConfigurationComponent(ConnectionConfigurationComponent connectionConfigurationComponent) {
        this.connectionConfigurationComponent = connectionConfigurationComponent;
    }

    private MappingComponent mappingComponent;

    @Autowired
    public void setMappingComponent(MappingComponent mappingStatusComponent) {
        this.mappingComponent = mappingStatusComponent;
    }

//    private MqttClient mqttClient;
    private KafkaConsumer<String, String> kafkaConsumer;

    private C8YAgent c8yAgent;

    @Autowired
    public void setC8yAgent(@Lazy C8YAgent c8yAgent) {
        this.c8yAgent = c8yAgent;
    }

    // @Autowired
    // private SynchronousDispatcher dispatcher;

    private AsynchronousDispatcher dispatcher;

    @Autowired
    public void setDispatcher(AsynchronousDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    @Autowired
    public void setCachedThreadPool(ExecutorService cachedThreadPool) {
        this.cachedThreadPool = cachedThreadPool;
    }

    private Future<Boolean> connectTask;
    private Future<Boolean> initializeTask;

    @Getter
    @Setter
    // keeps track of number of active mappings per subscriptionTopic
    private Map<String, MutableInt> activeSubscriptionMappingInbound;

    private Instant start = Instant.now();

    @Value("${APP.additionalSubscriptionIdTest}")
    private String additionalSubscriptionIdTest;

    @Data
    @AllArgsConstructor
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Called initialize(): {}", initializeTask == null || initializeTask.isDone());
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    private boolean initialize() {
        var firstRun = true;
        while (!canConnect()) {
            if (!firstRun) {
                try {
                    log.info("Retrieving MQTT configuration in {}s ...",
                            WAIT_PERIOD_MS / 1000);
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error initializing MQTT client: ", e);
                }
            }
            reloadConfiguration();
            if (connectionConfiguration.useSelfSignedCertificate) {
                cert = c8yAgent.loadCertificateByName(connectionConfiguration.nameCertificate);
            }
            firstRun = false;
        }
        return true;
    }

    private void reloadConfiguration() {
        connectionConfiguration = connectionConfigurationComponent.loadConnectionConfiguration();
    }

    public void submitConnect() {
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Called connect(): connectTask.isDone() {}",
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> connect());
        }
    }

    private boolean connect() throws Exception {
        reloadConfiguration();
        log.info("Establishing the MQTT connection now - phase I: (isConnected:shouldConnect) ({}:{})", isConnected(),
                shouldConnect());
        if (isConnected()) {
            disconnect();
        }
        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                log.info("Establishing the MQTT connection now - phase II: {}, {}",
                        ConfigurationConnection.isValid(connectionConfiguration), canConnect());
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        log.error("Error on reconnect: {}", e.getMessage());
                        log.debug("Stacktrace:", e);
                    }
                }
                    //if (canConnect()) {
                        if (kafkaConsumer != null) {
                        	kafkaConsumer.unsubscribe();
                        	kafkaConsumer.close();
                        }

                        initializeKafkaConsumer();
                        
                        //TODO: mqttClient.setCallback(dispatcher);

                        log.info("Successfully connected to Kafka");

                    //}
                firstRun = false;
            }

            try {
                // test if the mqtt connection is configured and enabled
                if (shouldConnect()) {
                    try {
                        // is not working for broker.emqx.io
                        subscribe("$SYS/#", 0);
                    } catch (Exception e) {
                        log.warn(
                                "Error on subscribing to topic $SYS/#, this might not be supported by the mqtt broker {} {}",
                                e.getMessage(), e);
                    }

                    mappingComponent.rebuildMappingOutboundCache();
                    // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                    // sync, the ActiveSubscriptionMappingInbound is build on the
                    // reviously used updatedMappings
                    List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache();
                    updateActiveSubscriptionMappingInbound(updatedMappings, true);
                }
                successful = true;
                log.info("Subscribing to topics was successful: {}", successful);
            } catch (Exception e) {
                log.error("Error on reconnect, retrying ... {} {}", e.getMessage(), e);
                log.debug("Stacktrace:", e);
                successful = false;
            }

        }
        return true;
    }

    private void initializeKafkaConsumer() {
		String username = "dghhrmxn";
		String password = "Qs40SS54to6_CAfRqdHwZu7GQxWE2OXE";

		String jaasTemplate = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";";
		String jaasCfg = String.format(jaasTemplate, username, password);
		String serializer = StringSerializer.class.getName();
		String deserializer = StringDeserializer.class.getName();

		Properties props = new Properties();
		props.put("bootstrap.servers", "glider.srvs.cloudkafka.com:9094");
		props.put("group.id", username + "-consumer");
		props.put("enable.auto.commit", "true");
		props.put("auto.commit.interval.ms", "1000");
		props.put("auto.offset.reset", "earliest");
		props.put("session.timeout.ms", "30000");
		props.put("key.deserializer", deserializer);
		props.put("value.deserializer", deserializer);
		props.put("key.serializer", serializer);
		props.put("value.serializer", serializer);
		props.put("security.protocol", "SASL_SSL");
		props.put("sasl.mechanism", "SCRAM-SHA-256");
		props.put("sasl.jaas.config", jaasCfg);
		props.put("linger.ms", 1);

		kafkaConsumer = new KafkaConsumer<>(props);		
		kafkaConsumer.subscribe(Arrays.asList("dghhrmxn-quickstart-events"));
		
		while (true) {
			ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(100));
			records.forEach(record -> {
				log.info("offset = {}, key = {}, value = {}", record.offset(), record.key(), record.value());
				//TODO: add message handling
			});;
		}		
		
	}

	private boolean canConnect() {
        return ConfigurationConnection.isEnabled(connectionConfiguration)
                && (!connectionConfiguration.useSelfSignedCertificate
                        || (connectionConfiguration.useSelfSignedCertificate &&
                                cert != null));
    }

    private boolean shouldConnect() {
        return !ConfigurationConnection.isValid(connectionConfiguration)
                || ConfigurationConnection.isEnabled(connectionConfiguration);
    }

    public boolean isConnected() {
        return kafkaConsumer != null;
    }

    public void disconnect() {
        log.info("Disconnecting from Kafka");
        if (isConnected()) {
            log.debug("Disconnected from MQTT broker I");
        	kafkaConsumer.unsubscribe();
        	kafkaConsumer.close();
        }
    }

    public void disconnectFromBroker() {
        connectionConfiguration = connectionConfigurationComponent.enableConnection(false);
        disconnect();
        mappingComponent.sendStatusService(getServiceStatus());
    }

    public void connectToBroker() {
        connectionConfiguration = connectionConfigurationComponent.enableConnection(true);
        submitConnect();
        mappingComponent.sendStatusService(getServiceStatus());
    }

    public void subscribe(String topic, Integer qos) throws MqttException {

        log.debug("Subscribing on topic: {}", topic);
        c8yAgent.createEvent("Subscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
		Set<String> topics = kafkaConsumer.listTopics().keySet();
		topics.add(topic);
    	kafkaConsumer.subscribe(topics);
        log.debug("Successfully subscribed on topic: {}", topic);
    }

    private void unsubscribe(String topic) throws MqttException {
        log.info("Unsubscribing from topic: {}", topic);
        c8yAgent.createEvent("Unsubscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
        unsubscribeFromTopic(topic);
    }

	private void unsubscribeFromTopic(String topic) {
		Set<String> topics = kafkaConsumer.listTopics().keySet();
		topics.remove(topic);
        kafkaConsumer.subscribe(topics);
	}

    @Scheduled(fixedRate = 30000)
    public void runHouskeeping() {
        try {
            Instant now = Instant.now();
            // only log this for the first 180 seconds to reduce log amount
            if (Duration.between(start, now).getSeconds() < 1800) {
                String statusConnectTask = (connectTask == null ? "stopped"
                        : connectTask.isDone() ? "stopped" : "running");
                String statusInitializeTask = (initializeTask == null ? "stopped"
                        : initializeTask.isDone() ? "stopped" : "running");
                log.info("Status: connectTask: {}, initializeTask: {}, isConnected: {}", statusConnectTask,
                        statusInitializeTask, isConnected());
            }
            mappingComponent.cleanDirtyMappings();
            mappingComponent.sendStatusMapping();
            mappingComponent.sendStatusService(getServiceStatus());
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

    public ServiceStatus getServiceStatus() {
        ServiceStatus serviceStatus;
        if (isConnected()) {
            serviceStatus = ServiceStatus.connected();
        } else if (canConnect()) {
            serviceStatus = ServiceStatus.activated();
        } else if (ConfigurationConnection.isValid(connectionConfiguration)) {
            serviceStatus = ServiceStatus.configured();
        } else {
            serviceStatus = ServiceStatus.notReady();
        }
        return serviceStatus;
    }


    public List<ProcessingContext<?>> test(String topic, boolean send, Map<String, Object> payload)
            throws Exception {
        String payloadMessage = objectMapper.writeValueAsString(payload);
        MqttMessage mqttMessage = new MqttMessage();
        mqttMessage.setPayload(payloadMessage.getBytes());
        return dispatcher.processMessage(topic, mqttMessage, send).get();
    }

    public void reconnect() {
        disconnect();
        // invalidate broker client
        connectionConfiguration = null;
        submitInitialize();
        submitConnect();
    }

    public void deleteActiveSubscriptionMappingInbound(Mapping mapping) {
        if (getActiveSubscriptionMappingInbound().containsKey(mapping.subscriptionTopic)) {
            MutableInt activeSubs = getActiveSubscriptionMappingInbound()
                    .get(mapping.subscriptionTopic);
            activeSubs.subtract(1);
            if (activeSubs.intValue() <= 0) {
                try {
                    unsubscribeFromTopic(mapping.subscriptionTopic);
                } catch (Exception e) {
                    log.error("Exception when unsubscribing from topic: {}, {}", mapping.subscriptionTopic,
                            e);
                }
            }
        }
    }

    public void upsertActiveSubscriptionMappingInbound(Mapping mapping) {
        // test if subsctiptionTopic has changed
        Mapping activeMapping = null;
        Boolean create = true;
        Boolean subscriptionTopicChanged = false;
        Optional<Mapping> activeMappingOptional = mappingComponent.getCacheMappingInbound().values().stream()
                .filter(m -> m.id.equals(mapping.id))
                .findFirst();

        if (activeMappingOptional.isPresent()) {
            create = false;
            activeMapping = activeMappingOptional.get();
            subscriptionTopicChanged = !mapping.subscriptionTopic.equals(activeMapping.subscriptionTopic);
        }

        if (!getActiveSubscriptionMappingInbound().containsKey(mapping.subscriptionTopic)) {
            getActiveSubscriptionMappingInbound().put(mapping.subscriptionTopic, new MutableInt(0));
        }
        MutableInt updatedMappingSubs = getActiveSubscriptionMappingInbound()
                .get(mapping.subscriptionTopic);

        // consider unsubscribing from previous subscription topic if it has changed
        if (create) {
            updatedMappingSubs.add(1);
            log.info("Subscribing to topic: {}, qos: {}", mapping.subscriptionTopic, mapping.qos.ordinal());
            try {
                subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
            } catch (MqttException e1) {
                log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
            }
        } else if (subscriptionTopicChanged && activeMapping != null) {
            MutableInt activeMappingSubs = getActiveSubscriptionMappingInbound()
                    .get(activeMapping.subscriptionTopic);
            activeMappingSubs.subtract(1);
            if (activeMappingSubs.intValue() <= 0) {
                try {
                    unsubscribeFromTopic(mapping.subscriptionTopic);
                } catch (Exception e) {
                    log.error("Exception when unsubscribing from topic: {}, {}", mapping.subscriptionTopic, e);
                }
            }
            updatedMappingSubs.add(1);
            if (!getActiveSubscriptionMappingInbound().containsKey(mapping.subscriptionTopic)) {
                log.info("Subscribing to topic: {}, qos: {}", mapping.subscriptionTopic, mapping.qos.ordinal());
                try {
                    subscribe(mapping.subscriptionTopic, mapping.qos.ordinal());
                } catch (MqttException e1) {
                    log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
                }
            }
        }

    }

    public List<Mapping> updateActiveSubscriptionMappingInbound(List<Mapping> updatedMappings, boolean reset) {
        if (reset) {
            activeSubscriptionMappingInbound = new HashMap<String, MutableInt>();
        }
        Map<String, MutableInt> updatedSubscriptionCache = new HashMap<String, MutableInt>();
        updatedMappings.forEach(mapping -> {
            if (!updatedSubscriptionCache.containsKey(mapping.subscriptionTopic)) {
                updatedSubscriptionCache.put(mapping.subscriptionTopic, new MutableInt(0));
            }
            MutableInt activeSubs = updatedSubscriptionCache.get(mapping.subscriptionTopic);
            activeSubs.add(1);
        });

        // unsubscribe topics not used
        getActiveSubscriptionMappingInbound().keySet().forEach((topic) -> {
            if (!updatedSubscriptionCache.containsKey(topic)) {
                log.info("Unsubscribe from topic: {}", topic);
                try {
                    unsubscribe(topic);
                } catch (MqttException e1) {
                    log.error("Exception when unsubscribing from topic: {}, {}", topic, e1);
                    throw new RuntimeException(e1);
                }
            }
        });

        // subscribe to new topics
        updatedSubscriptionCache.keySet().forEach((topic) -> {
            if (!getActiveSubscriptionMappingInbound().containsKey(topic)) {
                int qos = updatedMappings.stream().filter(m -> m.subscriptionTopic.equals(topic))
                        .map(m -> m.qos.ordinal()).reduce(Integer::max).orElse(0);
                log.info("Subscribing to topic: {}, qos: {}", topic, qos);
                try {
                    subscribe(topic, qos);
                } catch (MqttException e1) {
                    log.error("Exception when subscribing to topic: {}, {}", topic, e1);
                    throw new RuntimeException(e1);
                }
            }
        });
        activeSubscriptionMappingInbound = updatedSubscriptionCache;
        return updatedMappings;
    }

    public AbstractExtensibleRepresentation createMEAO(ProcessingContext<?> context)
            throws MqttPersistenceException, MqttException {
        MqttMessage mqttMessage = new MqttMessage();
        String payload = context.getCurrentRequest().getRequest();
        mqttMessage.setPayload(payload.getBytes());
        //TODO@Harald mqttClient.publish(context.getResolvedPublishTopic(), mqttMessage);
        log.info("Published outbound message: {} for mapping: {} ", payload, context.getMapping().name);
        return null;
    }

    public Map<String, Integer> getActiveSubscriptions() {
        return getActiveSubscriptionMappingInbound().entrySet().stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().getValue()))
                .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

}