package dynamic.mapping.connector.mqttconnect;

import com.cumulocity.microservice.context.credentials.Credentials;
import com.cumulocity.mqtt.service.client.MqttClient;
import com.cumulocity.mqtt.service.client.MqttConfig;
import com.cumulocity.mqtt.service.client.MqttPublisher;
import com.cumulocity.mqtt.service.client.MqttSubscriber;
import com.cumulocity.mqtt.service.client.model.MqttMessage;
import com.cumulocity.sdk.client.Platform;
import dynamic.mapping.configuration.ConnectorConfiguration;
import dynamic.mapping.connector.core.ConnectorProperty;
import dynamic.mapping.connector.core.ConnectorSpecification;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.ConfigurationRegistry;
import dynamic.mapping.processor.inbound.AsynchronousDispatcherInbound;
import dynamic.mapping.processor.model.C8YRequest;
import dynamic.mapping.processor.model.ProcessingContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.util.HashMap;
import java.util.Map;
@Slf4j
public class MQTTConnectClient extends AConnectorClient {

    private MqttClient mqttConnectClient;

    private Credentials credentials = null;

    @Getter
    private static final String connectorType = "MQTTConnect";

    private String connectorIdent = null;

    private String connectorName = null;

    @Getter
    public static ConnectorSpecification spec;
    static {
        Map<String, ConnectorProperty> configProps = new HashMap<>();
        spec = new ConnectorSpecification(connectorType, false, configProps);
    }

    private String additionalSubscriptionIdTest;

    private AConnectorClient.Certificate cert;

    private String baseUrl;

    private Platform platform;

    private String SUBSCRIBER_ID = "MQTTMapperSubscriber";

    private Map<String, MqttSubscriber> subscribers = new HashMap<>();

    public MQTTConnectClient(ConfigurationRegistry configurationRegistry,
                             ConnectorConfiguration connectorConfiguration, String additionalSubscriptionIdTest, String tenant, String baseUrl, Platform platform) {
        // setConfigProperties();
        this.credentials = credentials;
        this.tenant = tenant;
        this.mappingComponent = configurationRegistry.getMappingComponent();
        this.connectorConfigurationComponent = configurationRegistry.getConnectorConfigurationComponent();
        this.configuration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorIdent = connectorConfiguration.ident;
        this.connectorName = connectorConfiguration.name;
        this.c8yAgent = configurationRegistry.getC8yAgent();
        this.cachedThreadPool = configurationRegistry.getCachedThreadPool();
        this.objectMapper = configurationRegistry.getObjectMapper();
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
        this.baseUrl = baseUrl;
        this.platform = platform;
    }

    @Override
    public boolean initialize() {
        this.mqttConnectClient = MqttClient.webSocket()
                .url(getWebsocketUrl(baseUrl))
                .tokenApi(platform.getTokenApi())
                .build();
        return true;
    }

    private String getWebsocketUrl(String baseUrl) {
        if(baseUrl.startsWith("http"))
            return baseUrl.replace("http", "ws");
        if(baseUrl.startsWith("https"))
            return baseUrl.replace("https", "wss");
        return baseUrl;
    }

    private String getSubscriberId(String topic) {
        if(topic.contains("/"))
            topic = topic.replace("/", "");
        return SUBSCRIBER_ID + "_" + connectorIdent + "_" + topic;
    }

    @Override
    public ConnectorSpecification getSpecification() {
        return spec;
    }

    @Override
    public void connect() {
        //Just do nothing - MQTT Connect does not have a separate connect function...
    }

    @Override
    public boolean isConnected() {
        //When we have at least an active subscription this is considered to be connected
        if(subscribers.keySet().isEmpty())
            return false;
        else
            return true;
    }

    @Override
    public void disconnect() {
        this.mqttConnectClient.close();
    }

    @Override
    public void close() {
        this.mqttConnectClient.close();
    }

    @Override
    public String getConnectorIdent() {
        return connectorIdent;
    }

    @Override
    public String getConnectorName() {
        return connectorName;
    }

    @Override
    public void subscribe(String topic, Integer qos) throws MqttException {
        //FIXME We need for each topic an own subscriber ... this is bad practice and should be change in the MQTT Connect Client.
        log.debug("Subscribing on topic: {}", topic);
        MqttSubscriber subscriber = mqttConnectClient.buildSubscriber(MqttConfig.webSocket().subscriber(getSubscriberId(topic)).topic(topic).build());
        subscribers.put(getSubscriberId(topic), subscriber);
        if (dispatcher == null)
            this.dispatcher = new AsynchronousDispatcherInbound(configurationRegistry, this);
        MQTTConnectCallback callback = new MQTTConnectCallback(dispatcher, tenant, this.getConnectorIdent());
        subscriber.subscribe(callback);
        log.debug("Successfully subscribed on topic: {}", topic);
    }


    @Override
    public void unsubscribe(String topic) throws Exception {
        //How is this handled if the connection is terminated??? Is on reconnect the subscription still valid?
        String subscriberId = getSubscriberId(topic);
        subscribers.get(subscriberId).unsubscribe();
        subscribers.remove(subscriberId);
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        //This is always true as we don't have any config yet
        return true;
    }

    @Override
    public void publishMEAO(ProcessingContext<?> context) {
        MqttPublisher publisher = mqttConnectClient.buildPublisher(MqttConfig.webSocket().topic(context.getTopic()).build());
        MqttMessage mqttMessage = new MqttMessage();
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        mqttMessage.setPayload(payload.getBytes());
        publisher.publish(mqttMessage);
    }
}
