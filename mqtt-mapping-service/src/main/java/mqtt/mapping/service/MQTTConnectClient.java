package mqtt.mapping.service;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.mqtt.connect.client.MqttClient;
import com.cumulocity.mqtt.connect.client.MqttConfig;
import com.cumulocity.mqtt.connect.client.MqttMessageListener;
import com.cumulocity.mqtt.connect.client.MqttSubscriber;
import com.cumulocity.mqtt.connect.client.model.MqttMessage;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.sdk.client.Platform;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.core.ServiceStatus;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.inbound.AsynchronousDispatcher;
import mqtt.mapping.processor.model.ProcessingContext;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;


@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTConnectClient {

    @Value("${C8Y.baseURL}")
    private String baseUrl;

    private Instant start = Instant.now();

    private AsynchronousDispatcher dispatcher;

    @Autowired
    public void setDispatcher(AsynchronousDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    private static final int WAIT_PERIOD_MS = 10000;

    private Future<Boolean> connectTask;

    @Autowired
    private Platform platform;

    private C8YAgent c8yAgent;

    @Qualifier("cachedThreadPoolConnect")
    private ExecutorService cachedThreadPool;

    @Autowired
    public void setCachedThreadPool(ExecutorService cachedThreadPool) {
        this.cachedThreadPool = cachedThreadPool;
    }

    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";

    @Autowired
    public void setC8yAgent(@Lazy C8YAgent c8yAgent) {
        this.c8yAgent = c8yAgent;
    }

    @Autowired
    private MicroserviceSubscriptionsService contextService;

    @Getter
    @Setter
    // keeps track of number of active mappings per subscriptionTopic
    private Map<String, MutableInt> activeSubscriptionMappingInbound;

    private final String SUBSCRIBER_ID = "MQTTMapperSubscriber";

    private final Map<String, MqttSubscriber> subscribers = new HashMap<>();

    private MappingComponent mappingComponent;

    @Autowired
    public void setMappingComponent(MappingComponent mappingStatusComponent) {
        this.mappingComponent = mappingStatusComponent;
    }

    MqttClient client;

    public void initialize(String tenant) {
        if (baseUrl.contains("http")) {
            baseUrl = baseUrl.replace("http", "ws");
        } else if (baseUrl.contains("https")) {
            baseUrl = baseUrl.replace("https", "wss");
        }
        this.client = MqttClient.webSocket()
                .url(baseUrl)
                .tokenApi(platform.getTokenApi())
                .build();
    }

    public void publish(String topic, MqttMessage mqttMessage) {
        client.buildPublisher(MqttConfig.webSocket().build()).publish(mqttMessage);
    }

    public void subscribe(String topic) {
        contextService.runForEachTenant(() -> {
            //Assuming topic is uniquely subscribed by subscriber.
            MqttSubscriber subscriber = client.buildSubscriber(MqttConfig.webSocket().subscriber(SUBSCRIBER_ID).topic(topic).build());
            final String mqtttopic = topic;
            subscriber.subscribe(new MqttMessageListener() {
                @Override
                public void onMessage(final MqttMessage message) {
                    org.eclipse.paho.client.mqttv3.MqttMessage mqttMessage = new org.eclipse.paho.client.mqttv3.MqttMessage();
                    mqttMessage.setPayload(message.getPayload());
                    mqttMessage.setId(message.getMetadata().getMessageId());
                    //FIXME: QoS is missing in the response
                    mqttMessage.setQos(1);
                    try {
                        dispatcher.messageArrived(mqtttopic, mqttMessage);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    log.info("Received message {}", new String(message.getPayload()));
                }

                @Override
                public void onError(final Throwable throwable) {
                    log.error("Web socket error", throwable);
                }
            });
            subscribers.put(topic, subscriber);
        });
    }

    public void reconnect() {
        disconnect();
        // invalidate broker client
        submitConnect();
    }

    private boolean connect() throws Exception {
        log.info("Establishing the MQTT connection now - phase I");
        if (client != null) {
            // stay in the loop until successful
            boolean successful = false;
            while (!successful) {
                var firstRun = true;
                try {
                    // test if the mqtt connection is configured and enabled
                    mappingComponent.rebuildMappingOutboundCache();
                    // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                    // sync, the ActiveSubscriptionMappingInbound is build on the
                    // reviously used updatedMappings
                    List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache();
                    updateActiveSubscriptionMappingInbound(updatedMappings, true);

                    successful = true;
                    log.info("Subscribing to topics was successful: {}", successful);
                } catch (Exception e) {
                    log.error("Error on reconnect, retrying ... {} {}", e.getMessage(), e);
                    log.debug("Stacktrace:", e);
                    successful = false;
                }

            }
            return true;
        } else {
            return false;
        }
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

    @Scheduled(fixedRate = 30000)
    public void runHouskeeping() {
        try {
            Instant now = Instant.now();
            // only log this for the first 180 seconds to reduce log amount
            if (Duration.between(start, now).getSeconds() < 1800) {
                String statusConnectTask = (connectTask == null ? "stopped"
                        : connectTask.isDone() ? "stopped" : "running");
                //FIXME We don't have any connection status in the client
                log.info("Status: connectTask: {}, isConnected: {}", statusConnectTask, isConnected());
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
        } else {
            serviceStatus = ServiceStatus.notReady();
        }
        return serviceStatus;
    }

    public boolean isConnected() {
        return client != null ? true : false;
    }

    public void disconnect() {
        log.info("Disconnecting from MQTT connect endpoint");
        try {
            if (isConnected()) {
                log.debug("Disconnected from MQTT broker I");
                getActiveSubscriptionMappingInbound().entrySet().forEach(entry -> {
                    // only unsubscribe if still active subscriptions exist
                    String topic = entry.getKey();
                    MutableInt activeSubs = entry.getValue();
                    if (activeSubs.intValue() > 0) {
                        try {
                            unsubscribe(topic);
                        } catch (MqttException e) {
                            log.error("Exception when unsubscribing from topic: {}, {}", topic, e);
                        }

                    }
                });
                client.close();
                log.debug("Disconnected from MQTT endpoint II");
            }
        } catch (Exception e) {
            log.error("Error on disconnecting MQTT Client: ", e);
        }
    }


    private void unsubscribe(String topic) throws MqttException {
        log.info("Unsubscribing from topic: {}", topic);
        c8yAgent.createEvent("Unsubscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
        subscribers.get(topic).unsubscribe();
    }

    public void deleteActiveSubscriptionMappingInbound(Mapping mapping) {
        if (getActiveSubscriptionMappingInbound().containsKey(mapping.subscriptionTopic)) {
            MutableInt activeSubs = getActiveSubscriptionMappingInbound()
                    .get(mapping.subscriptionTopic);
            activeSubs.subtract(1);
            if (activeSubs.intValue() <= 0) {
                try {
                    unsubscribe(mapping.subscriptionTopic);
                } catch (MqttException e) {
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
                subscribe(mapping.subscriptionTopic);
            } catch (Exception e1) {
                log.error("Exception when subscribing to topic: {}, {}", mapping.subscriptionTopic, e1);
            }
        } else if (subscriptionTopicChanged && activeMapping != null) {
            MutableInt activeMappingSubs = getActiveSubscriptionMappingInbound()
                    .get(activeMapping.subscriptionTopic);
            activeMappingSubs.subtract(1);
            if (activeMappingSubs.intValue() <= 0) {
                try {
                    unsubscribe(mapping.subscriptionTopic);
                } catch (MqttException e) {
                    log.error("Exception when unsubscribing from topic: {}, {}", mapping.subscriptionTopic, e);
                }
            }
            updatedMappingSubs.add(1);
            if (!getActiveSubscriptionMappingInbound().containsKey(mapping.subscriptionTopic)) {
                log.info("Subscribing to topic: {}, qos: {}", mapping.subscriptionTopic, mapping.qos.ordinal());
                try {
                    subscribe(mapping.subscriptionTopic);
                } catch (Exception e1) {
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
                    //FIXME No QoS can be define on subscribe
                    //FIXME no error handling when trying to subscribe? Is there anything thrown?
                    subscribe(topic);
                } catch (Exception e1) {
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
        publish(context.getResolvedPublishTopic(), mqttMessage);
        log.info("Published outbound message: {} for mapping: {} ", payload, context.getMapping().name);
        return null;
    }

    public Map<String, Integer> getActiveSubscriptions() {
        return getActiveSubscriptionMappingInbound().entrySet().stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Integer>(entry.getKey(), entry.getValue().getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
