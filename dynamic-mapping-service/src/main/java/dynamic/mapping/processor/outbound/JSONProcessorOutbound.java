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

package dynamic.mapping.processor.outbound;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.EvaluateRuntimeException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import dynamic.mapping.model.Mapping;
import dynamic.mapping.model.MappingRepresentation;
import dynamic.mapping.model.MappingSubstitution;
import lombok.extern.slf4j.Slf4j;
import dynamic.mapping.connector.core.client.AConnectorClient;
import dynamic.mapping.core.C8YAgent;
import dynamic.mapping.processor.C8YMessage;
import dynamic.mapping.processor.ProcessingException;
import dynamic.mapping.processor.model.ProcessingContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
// @Service
public class JSONProcessorOutbound extends BasePayloadProcessorOutbound<JsonNode> {

    public JSONProcessorOutbound(ObjectMapper objectMapper, AConnectorClient connectorClient, C8YAgent c8yAgent,
            String tenant) {
        super(objectMapper, connectorClient, c8yAgent, tenant);
    }

    @Override
    public ProcessingContext<JsonNode> deserializePayload(ProcessingContext<JsonNode> context,
            C8YMessage c8yMessage) throws IOException {
        JsonNode jsonNode = objectMapper.readTree(c8yMessage.getPayload());
        context.setPayload(jsonNode);
        return context;
    }

    @Override
    public void extractFromSource(ProcessingContext<JsonNode> context)
            throws ProcessingException {
        Mapping mapping = context.getMapping();
        JsonNode payloadJsonNode = context.getPayload();
        Map<String, List<MappingSubstitution.SubstituteValue>> postProcessingCache = context.getPostProcessingCache();

        String payload = payloadJsonNode.toPrettyString();
        // log.info("Tenant {} -Patched payload: {}", tenant, payload);

        for (MappingSubstitution substitution : mapping.substitutions) {
            JsonNode extractedSourceContent = null;

            /*
             * step 1 extract content from inbound payload
             */
            var ps = substitution.pathSource;
            try {
                Expressions expr = Expressions.parse(ps);
                extractedSourceContent = expr.evaluate(payloadJsonNode);
            } catch (ParseException | IOException | EvaluateException e) {
                log.error("Tenant {} - Exception for: {}, {}", tenant, substitution.pathSource,
                        payload, e);
            } catch (EvaluateRuntimeException e) {
                log.error("Tenant {} -EvaluateRuntimeException for: {}, {}", tenant, substitution.pathSource,
                        payload, e);
            }
            /*
             * step 2 analyse exctracted content: textual, array
             */
            List<MappingSubstitution.SubstituteValue> postProcessingCacheEntry = postProcessingCache.getOrDefault(
                    substitution.pathTarget,
                    new ArrayList<MappingSubstitution.SubstituteValue>());
            if (extractedSourceContent == null) {
                log.error("No substitution for: {}, {}", substitution.pathSource,
                        payload);
                postProcessingCacheEntry
                        .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                MappingSubstitution.SubstituteValue.TYPE.IGNORE, substitution.repairStrategy));
                postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
            } else {
                if (extractedSourceContent.isArray()) {
                    if (substitution.expandArray) {
                        // extracted result from sourcPayload is an array, so we potentially have to
                        // iterate over the result, e.g. creating multiple devices
                        for (JsonNode jn : extractedSourceContent) {
                            if (jn.isTextual()) {
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.TEXTUAL,
                                                substitution.repairStrategy));
                            } else if (jn.isNumber()) {
                                postProcessingCacheEntry
                                        .add(new MappingSubstitution.SubstituteValue(jn,
                                                MappingSubstitution.SubstituteValue.TYPE.NUMBER,
                                                substitution.repairStrategy));
                            } else {
                                log.warn("Since result is not textual or number it is ignored: {}",
                                        jn.asText());
                            }
                        }
                        context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    } else {
                        // treat this extracted enry as single value, no MULTI_VALUE or MULTI_DEVICE
                        // substitution
                        context.addCardinality(substitution.pathTarget, 1);
                        postProcessingCacheEntry
                                .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                        MappingSubstitution.SubstituteValue.TYPE.ARRAY,
                                        substitution.repairStrategy));
                        postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                    }
                } else if (extractedSourceContent.isTextual()) {
                    if (ps.equals(MappingRepresentation.findDeviceIdentifier(mapping).pathSource)
                            && substitution.resolve2ExternalId) {
                        log.info("Tenant {} - Findind external Id: resolveGlobalId2ExternalId: {}, {}, {}, {}, {}",
                                tenant, ps, extractedSourceContent.toPrettyString(), extractedSourceContent.asText());
                        ExternalIDRepresentation externalId = c8yAgent.resolveGlobalId2ExternalId(tenant,
                                new GId(extractedSourceContent.asText()), mapping.externalIdType,
                                context);
                        if (externalId == null && context.isSendPayload()) {
                            throw new RuntimeException("External id " + extractedSourceContent.asText() + " for type "
                                    + mapping.externalIdType + " not found!");
                        } else if (externalId == null) {
                            extractedSourceContent = null;
                        } else {
                            extractedSourceContent = new TextNode(externalId.getExternalId());
                        }
                    }
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry.add(
                            new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.TEXTUAL, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else if (extractedSourceContent.isNumber()) {
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.NUMBER, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                } else {
                    log.info("This substitution, involves an objects for: {}, {}",
                            substitution.pathSource, extractedSourceContent.toString());
                    context.addCardinality(substitution.pathTarget, extractedSourceContent.size());
                    postProcessingCacheEntry
                            .add(new MappingSubstitution.SubstituteValue(extractedSourceContent,
                                    MappingSubstitution.SubstituteValue.TYPE.OBJECT, substitution.repairStrategy));
                    postProcessingCache.put(substitution.pathTarget, postProcessingCacheEntry);
                }
                if (c8yAgent.getServiceConfigurations().get(tenant).logSubstitution) {
                    log.info("Tenant {} - Evaluated substitution (pathSource:substitute)/({}:{}), (pathTarget)/({})",
                            tenant,
                            substitution.pathSource, extractedSourceContent.toString(), substitution.pathTarget);
                }
            }

        }

    }

}