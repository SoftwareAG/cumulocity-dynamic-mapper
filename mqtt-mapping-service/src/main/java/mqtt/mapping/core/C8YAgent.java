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

package mqtt.mapping.core;

import c8y.IsDevice;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionRemovedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.model.JSONBase;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.model.measurement.MeasurementValue;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.AbstractExtensibleRepresentation;
import com.cumulocity.rest.representation.alarm.AlarmRepresentation;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.rest.representation.tenant.auth.TrustedCertificateRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.alarm.AlarmApi;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.inventory.BinariesApi;
import com.cumulocity.sdk.client.measurement.MeasurementApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import mqtt.mapping.configuration.ConfigurationConnection;
import mqtt.mapping.configuration.ConnectionConfigurationComponent;
import mqtt.mapping.configuration.ServiceConfiguration;
import mqtt.mapping.configuration.ServiceConfigurationComponent;
import mqtt.mapping.model.*;
import mqtt.mapping.model.extension.ExtensionsComponent;
import mqtt.mapping.notification.C8YAPISubscriber;
import mqtt.mapping.processor.ProcessingException;
import mqtt.mapping.processor.extension.ExtensibleProcessor;
import mqtt.mapping.processor.extension.ProcessorExtension;
import mqtt.mapping.processor.inbound.BasePayloadProcessor;
import mqtt.mapping.processor.model.C8YRequest;
import mqtt.mapping.processor.model.MappingType;
import mqtt.mapping.processor.model.ProcessingContext;
import mqtt.mapping.service.MQTTClient;
import org.apache.commons.io.IOUtils;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.svenson.JSONParser;

import javax.annotation.PreDestroy;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

@Slf4j
@Service
public class C8YAgent implements ImportBeanDefinitionRegistrar {

    private static final String PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL = "mqtt.mapping.processor.extension.external";

    @Autowired
    private EventApi eventApi;

    @Autowired
    private InventoryFacade inventoryApi;

    @Autowired
    private BinariesApi binaryApi;

    @Autowired
    private IdentityFacade identityApi;

    @Autowired
    private MeasurementApi measurementApi;

    @Autowired
    private AlarmApi alarmApi;

    @Autowired
    private DeviceControlApi deviceControlApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private MQTTClient mqttClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConnectionConfigurationComponent connectionConfigurationComponent;

    @Autowired
    private ServiceConfigurationComponent serviceConfigurationComponent;

    @Autowired
    private MappingComponent mappingComponent;

    @Autowired
    private ExtensionsComponent extensions;

    @Autowired
    Map<MappingType, BasePayloadProcessor<?>> payloadProcessorsInbound;

    @Autowired
    C8YAPISubscriber operationSubscriber;

    private ExtensibleProcessor extensibleProcessor;

    private MappingServiceRepresentation mappingServiceRepresentation;

    private JSONParser jsonParser = JSONBase.getJSONParser();

    public String tenant = null;

    private MicroserviceCredentials credentials;

    @Getter
    @Setter
    private ServiceConfiguration serviceConfiguration;

    private static final String EXTENSION_INTERNAL_FILE = "extension-internal.properties";
    private static final String EXTENSION_EXTERNAL_FILE = "extension-external.properties";

    @EventListener
    public void destroy(MicroserviceSubscriptionRemovedEvent event) {
        log.info("Microservice unsubscribed for tenant {}", event.getTenant());
        //this.createEvent("MQTT Mapper Microservice terminated", "mqtt_microservice_stopevent", DateTime.now(), null);
        operationSubscriber.disconnect(null);
        if(mqttClient != null)
            mqttClient.disconnect();
    }
    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        tenant = event.getCredentials().getTenant();
        credentials = event.getCredentials();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        ManagedObjectRepresentation[] mappingServiceRepresentations = { new ManagedObjectRepresentation() };
        ServiceConfiguration[] serviceConfigurations = { null };
        // register agent
        subscriptionsService.runForTenant(tenant, () -> {

            ExternalIDRepresentation mappingServiceIdRepresentation = null;
            try {
                mappingServiceIdRepresentation = resolveExternalId(new ID(null, MappingServiceRepresentation.AGENT_ID),
                        null);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            if (mappingServiceIdRepresentation != null) {
                log.info("Agent with ID {} already exists {}", MappingServiceRepresentation.AGENT_ID,
                        mappingServiceIdRepresentation);
                mappingServiceRepresentations[0] = mappingServiceIdRepresentation.getManagedObject();
            } else {
                mappingServiceRepresentations[0].setName(MappingServiceRepresentation.AGENT_NAME);
                mappingServiceRepresentations[0].set(new Agent());
                mappingServiceRepresentations[0].set(new IsDevice());
                mappingServiceRepresentations[0].setProperty(MappingServiceRepresentation.MAPPING_STATUS_FRAGMENT,
                        new ArrayList<>());
                mappingServiceRepresentations[0] = inventoryApi.create(mappingServiceRepresentations[0], null);
                log.info("Agent has been created with ID {}", mappingServiceRepresentations[0].getId());
                ExternalIDRepresentation externalAgentId = identityApi.create(mappingServiceRepresentations[0],
                        new ID("c8y_Serial",
                                MappingServiceRepresentation.AGENT_ID),
                        null);
                log.debug("ExternalId created: {}", externalAgentId.getExternalId());
            }
            mappingServiceRepresentations[0] = inventoryApi.get(mappingServiceRepresentations[0].getId());

            extensibleProcessor = (ExtensibleProcessor) payloadProcessorsInbound.get(MappingType.PROCESSOR_EXTENSION);
            serviceConfigurations[0] = serviceConfigurationComponent.loadServiceConfiguration();

            // if managedObject for internal mapping extension exists
            ManagedObjectRepresentation[] internalExtensions = { null };
            extensions.getInternal().forEach(m -> {
                internalExtensions[0] = m;
            });
            if (internalExtensions[0] == null) {
                internalExtensions[0] = new ManagedObjectRepresentation();
                // internalExtensions[0].setProperty(ProcessorExtensionsRepresentation.PROCESSOR_EXTENSION_INTERNAL_TYPE,
                // new HashMap());
                Map<String, ?> props = Map.of("name",
                        ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                        "external", false);
                internalExtensions[0].setProperty(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE,
                        props);
                internalExtensions[0].setName(ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME);
                internalExtensions[0] = inventoryApi.create(internalExtensions[0], null);
            }
            log.info("Internal extension: {} registered: {}",
                    ExtensionsComponent.PROCESSOR_EXTENSION_INTERNAL_NAME,
                    internalExtensions[0].getId().getValue(), internalExtensions[0]);
            operationSubscriber.init();
            //operationSubscriber.subscribeTenant(tenant);
            //operationSubscriber.subscribeAllDevices();


        });
        serviceConfiguration = serviceConfigurations[0];
        loadProcessorExtensions();
        mappingServiceRepresentation = objectMapper.convertValue(mappingServiceRepresentations[0],
                MappingServiceRepresentation.class);
        mappingComponent.initializeMappingComponent(mappingServiceRepresentation);

        try {
            mqttClient.submitInitialize();
            mqttClient.submitConnect();
            mqttClient.runHouskeeping();
        } catch (Exception e) {
            log.error("Error on MQTT Connection: ", e);
            mqttClient.submitConnect();
        }
    }

    public void saveConnectionConfiguration(ConfigurationConnection configuration) throws JsonProcessingException {
        connectionConfigurationComponent.saveConnectionConfiguration(configuration);
        mqttClient.reconnect();
    }

    public MeasurementRepresentation storeMeasurement(ManagedObjectRepresentation mor,
            String eventType, DateTime timestamp, Map<String, Object> attributes, Map<String, Object> fragments)
            throws SDKException {

        MeasurementRepresentation[] measurementRepresentations = { new MeasurementRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            measurementRepresentations[0].setAttrs(attributes);
            measurementRepresentations[0].setSource(mor);
            measurementRepresentations[0].setType(eventType);
            measurementRepresentations[0].setDateTime(timestamp);

            // Step 3: Iterate over all fragments provided
            Iterator<Map.Entry<String, Object>> fragmentKeys = fragments.entrySet().iterator();
            while (fragmentKeys.hasNext()) {
                Map.Entry<String, Object> currentFragment = fragmentKeys.next();
                measurementRepresentations[0].set(currentFragment.getValue(), currentFragment.getKey());
            }
            measurementRepresentations[0] = measurementApi.create(measurementRepresentations[0]);
        });
        return measurementRepresentations[0];
    }

    public ExternalIDRepresentation resolveExternalId(ID identity, ProcessingContext<?> context) {
        if (identity.getType() == null) {
            identity.setType("c8y_Serial");
        }
        ExternalIDRepresentation[] externalIDRepresentations = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                externalIDRepresentations[0] = identityApi.getExternalId(identity, context);
            } catch (SDKException e) {
                log.warn("External ID {} not found", identity.getValue());
            }
        });
        return externalIDRepresentations[0];
    }

    public ExternalIDRepresentation findExternalId(GId gid, String idType, ProcessingContext<?> context) {
        if (idType == null) {
            idType = "c8y_Serial";
        }
        final String idt = idType;
        ExternalIDRepresentation[] result = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                result[0] = identityApi.findExternalId(gid, idt, context);
            } catch (SDKException e) {
                log.warn("External ID type {} for {} not found", idt, gid.getValue());
            }
        });
        return result[0];
    }

    public MappingServiceRepresentation getAgentMOR() {
        return mappingServiceRepresentation;
    }

    public MeasurementRepresentation createMeasurement(String name, String type, ManagedObjectRepresentation mor,
            DateTime dateTime, HashMap<String, MeasurementValue> mvMap) {
        MeasurementRepresentation measurementRepresentation = new MeasurementRepresentation();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                measurementRepresentation.set(mvMap, name);
                measurementRepresentation.setType(type);
                measurementRepresentation.setSource(mor);
                measurementRepresentation.setDateTime(dateTime);
                log.debug("Creating Measurement {}", measurementRepresentation);
                MeasurementRepresentation mrn = measurementApi.create(measurementRepresentation);
                measurementRepresentation.setId(mrn.getId());
            } catch (SDKException e) {
                log.error("Error creating Measurement", e);
            }
        });
        return measurementRepresentation;
    }

    public AlarmRepresentation createAlarm(String severity, String message, String type, DateTime alarmTime,
            ManagedObjectRepresentation parentMor) {
        AlarmRepresentation[] alarmRepresentations = { new AlarmRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            alarmRepresentations[0].setSeverity(severity);
            alarmRepresentations[0].setSource(parentMor);
            alarmRepresentations[0].setText(message);
            alarmRepresentations[0].setDateTime(alarmTime);
            alarmRepresentations[0].setStatus("ACTIVE");
            alarmRepresentations[0].setType(type);

            alarmRepresentations[0] = this.alarmApi.create(alarmRepresentations[0]);
        });
        return alarmRepresentations[0];
    }

    public void createEvent(String message, String type, DateTime eventTime, ManagedObjectRepresentation parentMor) {
        EventRepresentation[] eventRepresentations = { new EventRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            eventRepresentations[0].setSource(parentMor != null ? parentMor : mappingServiceRepresentation);
            eventRepresentations[0].setText(message);
            eventRepresentations[0].setDateTime(eventTime);
            eventRepresentations[0].setType(type);
            this.eventApi.createAsync(eventRepresentations[0]);
        });
    }

    public ArrayList<Mapping> getMappings() {
        ArrayList<Mapping> result = new ArrayList<Mapping>();
        subscriptionsService.runForTenant(tenant, () -> {
            result.addAll(mappingComponent.getMappings());
            log.info("Loaded mappings (inbound & outbound): {}", result.size());
        });
        return result;
    }

    public void saveMappings(List<Mapping> mappings) {
        subscriptionsService.runForTenant(tenant, () -> {
            mappingComponent.saveMappings(mappings);
        });
    }

    public Mapping createMapping(Mapping mapping) {
        Mapping[] mr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            mr[0] = mappingComponent.createMapping(mapping);
        });
        mqttClient.upsertInMappingCache(mr[0]);
        return mr[0];
    }

    public Mapping getMapping(String ident) {
        Mapping[] mr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            mr[0] = mappingComponent.getMapping(ident);
        });
        return mr[0];
    }

    public String deleteMapping(String id) throws Exception {
        Mapping[] mr = { null };
        Exception[] exceptions = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                mr[0] = mappingComponent.deleteMapping(id);
            } catch (Exception e) {
                exceptions[0] = e;
            }
        });
        if (exceptions[0] != null ) {
            throw exceptions[0];
        }
        mqttClient.deleteFromMappingCache(mr[0]);
        return mr[0].id;
    }

    public Mapping updateMapping(Mapping mapping, String id, boolean allowUpdateWhenActive) throws Exception {
        Mapping[] mr = { null };
        Exception[] exceptions = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                mr[0] = mappingComponent.updateMapping(mapping, allowUpdateWhenActive);
                log.info("Update Mapping {}", mr[0]);
            } catch (Exception e) {
                exceptions[0] = e;
            }
        });
        if (exceptions[0] != (null)) {
            throw exceptions[0];
        }
        mqttClient.upsertInMappingCache(mr[0]);
        return mr[0];
    }

    public MQTTClient.Certificate loadCertificateByName(String fingerprint) {
        TrustedCertificateRepresentation[] results = { new TrustedCertificateRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = serviceConfigurationComponent.loadCertificateByName(fingerprint, credentials);
            log.info("Found certificate with fingerprint: {}", results[0].getFingerprint());
        });
        StringBuffer cert = new StringBuffer("-----BEGIN CERTIFICATE-----\n").append(results[0].getCertInPemFormat())
                .append("\n").append("-----END CERTIFICATE-----");

        return new MQTTClient.Certificate(results[0].getFingerprint(), cert.toString());
    }

    public AbstractExtensibleRepresentation createMEAO(ProcessingContext<?> context) throws ProcessingException {
        String[] errors = { "" };
        AbstractExtensibleRepresentation[] results = { null };
        C8YRequest currentRequest = context.getCurrentRequest();
        String payload = currentRequest.getRequest();
        API targetAPI = context.getMapping().getTargetAPI();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                if (targetAPI.equals(API.EVENT)) {
                    EventRepresentation eventRepresentation = objectMapper.readValue(payload,
                            EventRepresentation.class);
                    results[0] = eventApi.create(eventRepresentation);
                    log.info("New event posted: {}", results[0]);
                } else if (targetAPI.equals(API.ALARM)) {
                    AlarmRepresentation alarmRepresentation = objectMapper.readValue(payload,
                            AlarmRepresentation.class);
                    results[0] = alarmApi.create(alarmRepresentation);
                    log.info("New alarm posted: {}", results[0]);
                } else if (targetAPI.equals(API.MEASUREMENT)) {
                    // MeasurementRepresentation mr = objectMapper.readValue(payload,
                    // MeasurementRepresentation.class);
                    MeasurementRepresentation measurementRepresentation = jsonParser
                            .parse(MeasurementRepresentation.class, payload);
                    results[0] = measurementApi.create(measurementRepresentation);
                    log.info("New measurement posted: {}", results[0]);
                } else if (targetAPI.equals(API.OPERATION)) {
                    OperationRepresentation operationRepresentation = jsonParser
                            .parse(OperationRepresentation.class, payload);
                    results[0] = deviceControlApi.create(operationRepresentation);
                    log.info("New operation posted: {}", results[0]);
                } else {
                    log.error("Not existing API!");
                }
            } catch (JsonProcessingException e) {
                log.error("Could not map payload: {} {}", targetAPI, payload);
                errors[0] = "Could not map payload: " + targetAPI + "/" + payload;
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {} {}", targetAPI, payload, s);
                errors[0] = "Could not sent payload to c8y: " + targetAPI + "/" + payload + "/" + s;
            }
        });
        if (!errors[0].equals("")) {
            throw new ProcessingException(errors[0]);
        }
        return results[0];
    }

    public ManagedObjectRepresentation upsertDevice(ID identity, ProcessingContext<?> context)
            throws ProcessingException {
        String[] errors = { "" };
        C8YRequest currentRequest = context.getCurrentRequest();
        ManagedObjectRepresentation[] devices = { new ManagedObjectRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                ExternalIDRepresentation extId = resolveExternalId(identity, context);
                if (extId == null) {
                    // Device does not exist
                    ManagedObjectRepresentation mor = objectMapper.readValue(currentRequest.getRequest(),
                            ManagedObjectRepresentation.class);
                    // append external id to name
                    mor.setName(mor.getName());
                    mor.set(new IsDevice());
                    mor = inventoryApi.create(mor, context);
                    log.info("New device created: {}", mor);
                    devices[0] = mor;
                    identityApi.create(mor, identity, context);
                } else {
                    // Device exists - update needed
                    ManagedObjectRepresentation mor = objectMapper.readValue(currentRequest.getRequest(),
                            ManagedObjectRepresentation.class);
                    mor.setId(extId.getManagedObject().getId());
                    mor = inventoryApi.update(mor, context);
                    devices[0] = mor;
                    log.info("Device updated: {}", mor);
                }

            } catch (JsonProcessingException e) {
                log.error("Could not map payload: {}", currentRequest.getRequest());
                errors[0] = "Could not map payload: " + currentRequest.getRequest();
            } catch (SDKException s) {
                log.error("Could not sent payload to c8y: {} {}", currentRequest.getRequest(), s);
                errors[0] = "Could not sent payload to c8y: " + currentRequest.getRequest() + " " + s;
            }
        });
        if (!errors[0].equals("")) {
            throw new ProcessingException(errors[0]);
        }
        return devices[0];
    }

    private void loadProcessorExtensions() {
        ClassLoader inernalClassloader = C8YAgent.class.getClassLoader();
        ClassLoader externalClassLoader = null;

        for (ManagedObjectRepresentation extension : extensions.get()) {
            Map<?, ?> props = (Map<?, ?>) (extension.get(ExtensionsComponent.PROCESSOR_EXTENSION_TYPE));
            String extName = props.get("name").toString();
            boolean external = (Boolean) props.get("external");
            log.info("Trying to load extension id: {}, name: {}, ", extension.getId().getValue(), extName);

            try {
                if (external) {
                    // step 1 download extension for binary repository
                    InputStream downloadInputStream = binaryApi.downloadFile(extension.getId());

                    // step 2 create temporary file,because classloader needs a url resource
                    File tempFile;
                    tempFile = File.createTempFile(extName, "jar");
                    tempFile.deleteOnExit();
                    String canonicalPath = tempFile.getCanonicalPath();
                    String path = tempFile.getPath();
                    String pathWithProtocol = "file://".concat(tempFile.getPath());
                    log.info("CanonicalPath: {}, Path: {}, PathWithProtocol: {}", canonicalPath, path,
                            pathWithProtocol);
                    FileOutputStream outputStream = new FileOutputStream(tempFile);
                    IOUtils.copy(downloadInputStream, outputStream);

                    // step 3 parse list of extentions
                    URL[] urls = { tempFile.toURI().toURL() };
                    externalClassLoader = new URLClassLoader(urls, mqtt.mapping.App.class.getClassLoader());
                    registerExtensionInProcessor(extension.getId().getValue(), extName, externalClassLoader, external);
                } else {
                    registerExtensionInProcessor(extension.getId().getValue(), extName, inernalClassloader, external);
                }
            } catch (IOException e) {
                log.error("Exception occured, When loading extension, starting without extensions!", e);
                // e.printStackTrace();
            }
        }
    }

    private void registerExtensionInProcessor(String id, String extName, ClassLoader dynamicLoader, boolean external)
            throws IOException {
        extensibleProcessor.addExtension(id, extName, external);
        String resource = external ? EXTENSION_EXTERNAL_FILE : EXTENSION_INTERNAL_FILE;
        InputStream resourceAsStream = dynamicLoader.getResourceAsStream(resource);
        InputStreamReader in = null;
        try {
            in = new InputStreamReader(resourceAsStream);
        } catch (Exception e) {
            log.error("Registration file: {} missing, ignoring to load extensions from: {}", resource,
                    (external ? "EXTERNAL" : "INTERNAL"));
            throw new IOException("Registration file: " + resource + " missing, ignoring to load extensions from:"
                    + (external ? "EXTERNAL" : "INTERNAL"));
        }
        BufferedReader buffered = new BufferedReader(in);
        Properties newExtensions = new Properties();

        if (buffered != null)
            newExtensions.load(buffered);
        log.info("Preparing to load extensions:" + newExtensions.toString());

        Enumeration<?> extensions = newExtensions.propertyNames();
        while (extensions.hasMoreElements()) {
            String key = (String) extensions.nextElement();
            Class<?> clazz;
            ExtensionEntry extensionEntry = new ExtensionEntry(key, newExtensions.getProperty(key),
                    null, true, "OK");
            extensibleProcessor.addExtensionEntry(extName, extensionEntry);

            try {
                clazz = dynamicLoader.loadClass(newExtensions.getProperty(key));
                if (external && !clazz.getPackageName().startsWith(PACKAGE_MAPPING_PROCESSOR_EXTENSION_EXTERNAL)) {
                    extensionEntry.setMessage(
                            "Implementation must be in package: 'mqtt.mapping.processor.extension.external' instead of: "
                                    + clazz.getPackageName());
                    extensionEntry.setLoaded(false);
                } else {
                    Object object = clazz.getDeclaredConstructor().newInstance();
                    if (!(object instanceof ProcessorExtension)) {
                        String msg = String.format(
                                "Extension: %s=%s is not instance of ProcessorExtension, ignoring this entry!", key,
                                newExtensions.getProperty(key));
                        log.warn(msg);
                        extensionEntry.setLoaded(false);
                    } else {
                        ProcessorExtension<?> extensionImpl = (ProcessorExtension<?>) clazz.getDeclaredConstructor()
                                .newInstance();
                        // springUtil.registerBean(key, clazz);
                        extensionEntry.setExtensionImplementation(extensionImpl);
                        log.info("Sucessfully registered bean: {} for key: {}", newExtensions.getProperty(key),
                                key);
                    }
                }
            } catch (Exception e) {
                String msg = String.format("Could not load extension: %s:%s, ignoring loading!", key,
                        newExtensions.getProperty(key));
                log.warn(msg);
                e.printStackTrace();
                extensionEntry.setLoaded(false);
            }
        }
        extensibleProcessor.updateStatusExtension(extName);
    }

    public Map<String, Extension> getProcessorExtensions() {
        return extensibleProcessor.getExtensions();
    }

    public Extension getProcessorExtension(String extension) {
        return extensibleProcessor.getExtension(extension);
    }

    public Extension deleteProcessorExtension(String extensionName) {
        for (ManagedObjectRepresentation extensionRepresentation : extensions.get()) {
            if (extensionName.equals(extensionRepresentation.getName())) {
                binaryApi.deleteFile(extensionRepresentation.getId());
                log.info("Deleted extension: {} permanently!", extensionName);
            }
        }
        return extensibleProcessor.deleteExtension(extensionName);
    }

    public void reloadExtensions() {
        extensibleProcessor.deleteExtensions();
        loadProcessorExtensions();
    }

    public ServiceConfiguration loadServiceConfiguration() {
        ServiceConfiguration[] results = { new ServiceConfiguration() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = serviceConfigurationComponent.loadServiceConfiguration();
            log.info("Found service configuration: {}", results[0]);
        });
        return results[0];
    }

    public ConfigurationConnection loadConnectionConfiguration() {
        ConfigurationConnection[] results = { new ConfigurationConnection() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = connectionConfigurationComponent.loadConnectionConfiguration();
            log.info("Found connection configuration: {}", results[0]);
        });
        return results[0];
    }

    public void sendStatusMapping() {
        subscriptionsService.runForTenant(tenant, () -> {
            mappingComponent.sendStatusMapping();
        });
    }

    public void sendStatusService(ServiceStatus serviceStatus) {
        subscriptionsService.runForTenant(tenant, () -> {
            mappingComponent.sendStatusService(serviceStatus);
        });
    }

    public void cleanDirtyMappings() throws Exception {
        // test if for this tenant dirty mappings exist
        log.debug("Testing for dirty maps");
        for (Mapping mapping : mappingComponent.getMappingDirty()) {
            log.info("Found mapping to be saved: {}, {}", mapping.id, mapping.snoopStatus);
            // no reload required
            updateMapping(mapping, mapping.id, true);
        }
        // reset dirtySet
        mappingComponent.resetMappingDirty();
    }

    public void setActivationMapping(Map<String, String> parameter) throws Exception {
        // step 1. update activation for mapping
        String id = parameter.get("id");
        String active = parameter.get("active");
        Boolean activeBoolean = Boolean.parseBoolean(active);
        log.info("Setting active: {} got mapping: {}, {}", id, active, activeBoolean);
        Mapping mapping = mappingComponent.getMapping(id);
        mapping.setActive(activeBoolean);
        // step 2. retrieve collected snoopedTemplates
        mqttClient.getActiveInboundMappings().values().forEach(m -> {
            if (m.id == id) {
                mapping.setSnoopedTemplates(m.getSnoopedTemplates());
            }
        });
        // step 3. update mapping in inventory
        updateMapping(mapping, id, true);
        // step 4 delete mapping from update cache
        mappingComponent.removeMappingFormDirtyMappings(mapping);
    }

    public ManagedObjectRepresentation getManagedObjectForId(String deviceId) {
        ManagedObjectRepresentation[] devices = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            GId id = new GId();
            id.setValue(deviceId);
            try {
                ManagedObjectRepresentation mor = inventoryApi.get(id);
                devices[0] = mor;
            } catch (SDKException exception) {
                log.warn("Device with id {} not found!", deviceId);
            }
        });

        return devices[0];
    }

    public void updateOperationStatus(OperationRepresentation op, OperationStatus status, String failureReason) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                op.setStatus(status.toString());
                if (failureReason != null)
                    op.setFailureReason(failureReason);
                deviceControlApi.update(op);
            } catch (SDKException exception) {
                log.error("Operation with id {} could not be updated: {}", op.getDeviceId().getValue(), exception.getLocalizedMessage());
            }
        });
    }
}
