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

package mqtt.mapping.connector;

import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ConnectorConfigurationComponent {
    private static final String OPTION_CATEGORY_CONFIGURATION = "dynamic.mapper.service";
    private static final String OPTION_KEY_CONNECTION_CONFIGURATION = "credentials.connection.configuration";
    private static final String OPTION_KEY_SERVICE_CONFIGURATION = "service.configuration";

    private final TenantOptionApi tenantOptionApi;

    @Getter
    @Setter
    private String tenant = null;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    private ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired
    public ConnectorConfigurationComponent(TenantOptionApi tenantOptionApi) {
        this.tenantOptionApi = tenantOptionApi;
    }


    public String getConnectorOptionKey(String connectorId) {
        return OPTION_KEY_CONNECTION_CONFIGURATION+"."+connectorId;
    }
    public void saveConnectionConfiguration(final ConnectorConfiguration configuration)
            throws JsonProcessingException {
        if (configuration == null) {
            return;
        }
        String connectorId = configuration.getConnectorId();
        final String configurationJson = objectMapper.writeValueAsString(configuration);
        final OptionRepresentation optionRepresentation = OptionRepresentation.asOptionRepresentation(OPTION_CATEGORY_CONFIGURATION, getConnectorOptionKey(connectorId), configurationJson);
        tenantOptionApi.save(optionRepresentation);
    }

    public ConnectorConfiguration loadConnectorConfiguration(String connectorId) {
        final OptionPK option = new OptionPK();
        option.setCategory(OPTION_CATEGORY_CONFIGURATION);
        option.setKey(getConnectorOptionKey(connectorId));
        ConnectorConfiguration result =  subscriptionsService.callForTenant(tenant, () -> {
            ConnectorConfiguration rt = null;
            try {
                final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
                final ConnectorConfiguration configuration = new ObjectMapper().readValue(
                        optionRepresentation.getValue(),
                        ConnectorConfiguration.class);
                log.debug("Returning connection configuration found: {}:", configuration.getConnectorId());
                rt = configuration;
            } catch (SDKException exception) {
                log.warn("No configuration found, returning empty element!");
                rt = null;
            } catch (JsonMappingException e) {
                e.printStackTrace();
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            return rt;
        });
        return result;
    }

    public List<ConnectorConfiguration> loadAllConnectorConfiguration() {
        final OptionPK option = new OptionPK();
        final List<ConnectorConfiguration> connectorConfigurations = new ArrayList<>();
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                final List<OptionRepresentation> optionRepresentationList = tenantOptionApi.getAllOptionsForCategory(OPTION_CATEGORY_CONFIGURATION);
                for (OptionRepresentation optionRepresentation : optionRepresentationList) {
                    //Just Connector Config --> Ignoring Service Configuration
                    if (optionRepresentation.getKey().startsWith(OPTION_KEY_CONNECTION_CONFIGURATION)) {
                        final ConnectorConfiguration configuration = new ObjectMapper().readValue(
                                optionRepresentation.getValue(),
                                ConnectorConfiguration.class);
                        connectorConfigurations.add(configuration);
                        log.debug("Connection configuration found: {}:", configuration.getConnectorId());
                    }
                }
            } catch (SDKException exception) {
                log.warn("No configuration found, returning empty element!");
            } catch (JsonMappingException e) {
                e.printStackTrace();
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        });
        return connectorConfigurations;
    }

    public void deleteAllConfiguration() {
        List<ConnectorConfiguration> configs = loadAllConnectorConfiguration();
        for (ConnectorConfiguration config : configs) {
            OptionPK optionPK = new OptionPK(OPTION_CATEGORY_CONFIGURATION, getConnectorOptionKey(config.getConnectorId()));
            tenantOptionApi.delete(optionPK);
        }
        OptionPK optionPK= new OptionPK(OPTION_CATEGORY_CONFIGURATION, OPTION_KEY_SERVICE_CONFIGURATION);
        tenantOptionApi.delete(optionPK);
    }

    public ConnectorConfiguration enableConnection(String connectorId, boolean enabled) {
        final OptionPK option = new OptionPK(OPTION_CATEGORY_CONFIGURATION, getConnectorOptionKey(connectorId));
        try {
            final OptionRepresentation optionRepresentation = tenantOptionApi.getOption(option);
            final ConnectorConfiguration configuration = new ObjectMapper().readValue(optionRepresentation.getValue(),
                    ConnectorConfiguration.class);
            configuration.enabled = enabled;
            log.debug("Setting connection: {}:", configuration.enabled);
            final String configurationJson = new ObjectMapper().writeValueAsString(configuration);
            optionRepresentation.setCategory(OPTION_CATEGORY_CONFIGURATION);
            optionRepresentation.setKey(getConnectorOptionKey(connectorId));
            optionRepresentation.setValue(configurationJson);
            tenantOptionApi.save(optionRepresentation);
            return configuration;
        } catch (SDKException exception) {
            log.warn("No configuration found, returning empty element!");
            // exception.printStackTrace();
        } catch (JsonMappingException e) {
            e.printStackTrace();
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    }

}
