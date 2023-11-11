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

package mqtt.mapping.connector.mqtt;

import com.cumulocity.microservice.context.credentials.Credentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConnectorConfiguration;
import mqtt.mapping.configuration.ConnectorConfigurationComponent;
import mqtt.mapping.connector.core.ConnectorProperty;
import mqtt.mapping.connector.core.ConnectorPropertyDefinition;
import mqtt.mapping.connector.core.client.IConnectorClient;
import mqtt.mapping.core.C8YAgent;
import mqtt.mapping.core.MappingComponent;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.processor.inbound.AsynchronousDispatcher;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.ProcessingContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.joda.time.DateTime;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Slf4j
// @EnableScheduling 
// @Configuration
// @Service
// This is instantiated manually not using Spring Boot anymore.
public class MQTTClient extends AConnectorClient {

    public MQTTClient(Credentials credentials, String tenant, MappingComponent mappingComponent,
            ConnectorConfigurationComponent connectorConfigurationComponent,
            ConnectorConfiguration connectorConfiguration, C8YAgent c8YAgent, ExecutorService cachedThreadPool,
            ObjectMapper objectMapper, String additionalSubscriptionIdTest) {
        // setConfigProperties();
        this.credentials = credentials;
        this.tenant = tenant;
        this.mappingComponent = mappingComponent;
        this.connectorConfigurationComponent = connectorConfigurationComponent;
        this.configuration = connectorConfiguration;
        // ensure the client knows its identity even if configuration is set to null
        this.connectorIdent = connectorConfiguration.ident;
        this.c8yAgent = c8YAgent;
        this.cachedThreadPool = cachedThreadPool;
        this.objectMapper = objectMapper;
        this.additionalSubscriptionIdTest = additionalSubscriptionIdTest;
    }

    private static final int WAIT_PERIOD_MS = 10000;
  
    private Credentials credentials = null;

    private static final String CONNECTOR_ID = "MQTT";

    private String connectorIdent = null;

    @Getter
    public static Map<String, ConnectorPropertyDefinition> configProps;
    static {
        configProps = new HashMap<>();
        configProps.put("mqttHost", new ConnectorPropertyDefinition(true, ConnectorProperty.STRING_PROPERTY));
        configProps.put("mqttPort", new ConnectorPropertyDefinition(true, ConnectorProperty.NUMERIC_PROPERTY));
        configProps.put("user", new ConnectorPropertyDefinition(false, ConnectorProperty.STRING_PROPERTY));
        configProps.put("password",
                new ConnectorPropertyDefinition((false), ConnectorProperty.SENSITIVE_STRING_PROPERTY));
        configProps.put("clientId", new ConnectorPropertyDefinition(true, ConnectorProperty.STRING_PROPERTY));
        configProps.put("useTLS", new ConnectorPropertyDefinition(false, ConnectorProperty.BOOLEAN_PROPERTY));
        configProps.put("useSelfSignedCertificate",
                new ConnectorPropertyDefinition(false, ConnectorProperty.BOOLEAN_PROPERTY));
        configProps.put("fingerprintSelfSignedCertificate",
                new ConnectorPropertyDefinition(false, ConnectorProperty.STRING_PROPERTY));
        configProps.put("nameCertificate", new ConnectorPropertyDefinition(false, ConnectorProperty.STRING_PROPERTY));
    }

    private String additionalSubscriptionIdTest;

    private IConnectorClient.Certificate cert;

    private MQTTCallback mqttCallback = null;

    private MqttClient mqttClient;

    public boolean initialize() {
        var firstRun = true;
        while (!canConnect()) {
            // this.configuration =
            // connectorConfigurationComponent.loadConnectorConfiguration(this.getConntectorIdent());
            if (!firstRun) {
                try {
                    log.info("Tenant {} - Retrieving MQTT configuration in {}s ...", tenant,
                            WAIT_PERIOD_MS / 1000);
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error initializing MQTT client: ", e);
                }
            }
            reloadConfiguration();

            boolean useSelfSignedCertificate = (Boolean) configuration.getProperties().get("useSelfSignedCertificate");
            String nameCertificate = (String) configuration.getProperties().get("nameCertificate");
            if (useSelfSignedCertificate) {
                cert = c8yAgent.loadCertificateByName(nameCertificate, this.credentials);
            }
            firstRun = false;
        }
        return true;
    }

    @Override
    public Map<String, ConnectorPropertyDefinition> getConfigProperties() {
        return MQTTClient.configProps;
    }

    public void connect() {
        log.info("Tenant {} - Establishing the MQTT connection now - phase I: (isConnected:shouldConnect) ({}:{})",
                tenant, isConnected(),
                shouldConnect());
        if (isConnected()) {
            disconnect();
        }
        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            reloadConfiguration();
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                log.info("Tenant {} - Establishing the MQTT connection now - phase II: {}, {}", tenant,
                        isConfigValid(configuration), canConnect());
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        log.error("Teanant {} - Error on reconnect: {}", tenant, e.getMessage());
                        log.debug("Stacktrace:", e);
                    }
                }
                try {
                    if (canConnect()) {
                        boolean useTLS = (Boolean) configuration.getProperties().getOrDefault("useTLS", false);
                        boolean useSelfSignedCertificate = (Boolean) configuration.getProperties()
                                .getOrDefault("useSelfSignedCertificate", false);
                        String prefix = useTLS ? "ssl://" : "tcp://";
                        String mqttHost = (String) configuration.getProperties().get("mqttHost");
                        String clientId = (String) configuration.getProperties().get("clientId");
                        int mqttPort = (Integer) configuration.getProperties().get("mqttPort");
                        String user = (String) configuration.getProperties().get("user");
                        String password = (String) configuration.getProperties().get("password");
                        String broker = prefix + mqttHost + ":"
                                + mqttPort;
                        // mqttClient = new MqttClient(broker, MqttClient.generateClientId(), new
                        // MemoryPersistence());

                        // before we create a new mqttClient, test if there already exists on and try to
                        // close it
                        if (mqttClient != null) {
                            mqttClient.close(true);
                        }
                        if (dispatcher == null)
                            this.dispatcher = new AsynchronousDispatcher(this, c8yAgent, objectMapper, cachedThreadPool,
                                    mappingComponent);
                        mqttClient = new MqttClient(broker,
                                clientId + additionalSubscriptionIdTest,
                                new MemoryPersistence());
                        mqttCallback = new MQTTCallback(dispatcher, tenant, getConntectorId());
                        mqttClient.setCallback(mqttCallback);
                        MqttConnectOptions connOpts = new MqttConnectOptions();
                        connOpts.setCleanSession(true);
                        connOpts.setAutomaticReconnect(false);
                        // log.info("Tenant {} - DANGEROUS-LOG password: {}", tenant, password);
                        if (!StringUtils.isEmpty(user)
                                && !StringUtils.isEmpty(password)) {
                            connOpts.setUserName(user);
                            connOpts.setPassword(password.toCharArray());
                        }
                        if (useSelfSignedCertificate) {
                            log.debug("Using certificate: {}", cert.getCertInPemFormat());

                            try {
                                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                                trustStore.load(null, null);
                                trustStore.setCertificateEntry("Custom CA",
                                        (X509Certificate) CertificateFactory.getInstance("X509")
                                                .generateCertificate(new ByteArrayInputStream(
                                                        cert.getCertInPemFormat().getBytes(Charset.defaultCharset()))));

                                TrustManagerFactory tmf = TrustManagerFactory
                                        .getInstance(TrustManagerFactory.getDefaultAlgorithm());
                                tmf.init(trustStore);
                                TrustManager[] trustManagers = tmf.getTrustManagers();

                                SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                                sslContext.init(null, trustManagers, null);
                                SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                                // where options is the MqttConnectOptions object
                                connOpts.setSocketFactory(sslSocketFactory);
                            } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException
                                    | KeyManagementException e) {
                                log.error("Exception when configuring socketFactory for TLS!", e);
                            }
                        }
                        mqttClient.connect(connOpts);
                        log.info("Tenant {} - Successfully connected to broker {}", tenant,
                                mqttClient.getServerURI());
                        c8yAgent.createEvent("Successfully connected to broker " + mqttClient.getServerURI(),
                                STATUS_MQTT_EVENT_TYPE,
                                DateTime.now(), null, tenant);

                    }
                } catch (MqttException e) {
                    log.error("Error on reconnect: {}", e.getMessage());
                    log.debug("Stacktrace:", e);
                }
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

                    mappingComponent.rebuildMappingOutboundCache(tenant);
                    // in order to keep MappingInboundCache and ActiveSubscriptionMappingInbound in
                    // sync, the ActiveSubscriptionMappingInbound is build on the
                    // reviously used updatedMappings
                    List<Mapping> updatedMappings = mappingComponent.rebuildMappingInboundCache(tenant);
                    updateActiveSubscriptions(updatedMappings, true);
                }
                successful = true;
                log.info("Tenant {} - Subscribing to topics was successful: {}", tenant, successful);
            } catch (Exception e) {
                log.error("Teanant {} - Error on reconnect, retrying ... {} {}", tenant, e.getMessage(), e);
                log.debug("Stacktrace:", e);
                successful = false;
            }

        }
    }

    public boolean canConnect() {
        Map<String, Object> p = configuration.getProperties();
        if (configuration == null)
            return false;
        Boolean useSelfSignedCertificate = (Boolean) p.getOrDefault("useSelfSignedCertificate", false);
        return configuration.isEnabled()
                && (!useSelfSignedCertificate
                        || (useSelfSignedCertificate &&
                                cert != null));
    }

    public boolean shouldConnect() {
        return isConfigValid(configuration) && configuration.isEnabled();
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        log.info("Tenant {} - is connecting from MQTT broker: {}", tenant,
                (mqttClient == null ? null : mqttClient.getServerURI()));
        try {
            if (isConnected()) {
                log.debug("Disconnected from MQTT broker I: {}", mqttClient.getServerURI());
                activeSubscriptions.entrySet().forEach(entry -> {
                    // only unsubscribe if still active subscriptions exist
                    String topic = entry.getKey();
                    MutableInt activeSubs = entry.getValue();
                    if (activeSubs.intValue() > 0) {
                        try {
                            mqttClient.unsubscribe(topic);
                        } catch (MqttException e) {
                            log.error("Exception when unsubscribing from topic: {}, {}", topic, e);
                        }

                    }
                });
                mqttClient.unsubscribe("$SYS");
                mqttClient.disconnect();
                log.debug("Disconnected from MQTT broker II: {}", mqttClient.getServerURI());
            }
        } catch (MqttException e) {
            log.error("Error on disconnecting MQTT Client: ", e);
        }
    }

    @Override
    public String getConntectorId() {
        return CONNECTOR_ID;
    }

    @Override
    public String getConntectorIdent() {
        return connectorIdent;
    }

    public void disconnectFromBroker() {
        configuration = connectorConfigurationComponent.enableConnection(this.getConntectorIdent(), false);
        disconnect();
        mappingComponent.sendConnectorStatus(tenant, getConnectorStatus(), getConntectorIdent());
    }

    public void connectToBroker() {
        configuration = connectorConfigurationComponent.enableConnection(this.getConntectorIdent(), true);
        submitConnect();
        mappingComponent.sendConnectorStatus(tenant, getConnectorStatus(), getConntectorIdent());
    }

    @Override
    public void subscribe(String topic, Integer qos) throws MqttException {
        log.debug("Subscribing on topic: {}", topic);
        c8yAgent.createEvent("Subscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null, tenant);
        if (qos != null)
            mqttClient.subscribe(topic, qos);
        else
            mqttClient.subscribe(topic);
        log.debug("Successfully subscribed on topic: {}", topic);
    }

    public void unsubscribe(String topic) throws Exception {
        log.info("Tenant {} - Unsubscribing from topic: {}", tenant, topic);
        c8yAgent.createEvent("Unsubscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null, tenant);
        mqttClient.unsubscribe(topic);
    }

    @Override
    public boolean isConfigValid(ConnectorConfiguration configuration) {
        if (configuration == null)
            return false;
        String host = (String) configuration.getProperties().get("mqttHost");
        int port = (Integer) configuration.getProperties().get("mqttPort");
        String clientId = (String) configuration.getProperties().get("clientId");
        return !StringUtils.isEmpty(host) &&
                !(port == 0) &&
                !StringUtils.isEmpty(clientId);
    }

    public static String getConnectorId() {
        return CONNECTOR_ID;
    }

    public void publishMEAO(ProcessingContext<?> context) {
        MqttMessage mqttMessage = new MqttMessage();
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        mqttMessage.setPayload(payload.getBytes());
        try {
            mqttClient.publish(context.getResolvedPublishTopic(), mqttMessage);
        } catch (MqttException e) {
            throw new RuntimeException(e);
        }
        log.info("Tenant {} - Published outbound message: {} for mapping: {} on topic: {}", tenant, payload,
                context.getMapping().name, context.getResolvedPublishTopic());
    }

}