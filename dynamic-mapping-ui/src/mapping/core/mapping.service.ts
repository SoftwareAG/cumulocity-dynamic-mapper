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
 * @authors Christof Strack
 */
import { Injectable } from '@angular/core';
import {
  FetchClient,
  IFetchResponse,
  IIdentified,
  InventoryService,
  QueriesUtil
} from '@c8y/client';
import { Subject } from 'rxjs';
import { BrokerConfigurationService, Operation } from '../../configuration';
import {
  BASE_URL,
  MAPPING_FRAGMENT,
  MAPPING_TYPE,
  PATH_MAPPING_ENDPOINT,
  PATH_SUBSCRIPTIONS_ENDPOINT,
  PATH_SUBSCRIPTION_ENDPOINT,
  Direction,
  Mapping,
  SharedService
} from '../../shared';
import { JSONProcessorInbound } from '../processor/impl/json-processor-inbound.service';
import { JSONProcessorOutbound } from '../processor/impl/json-processor-outbound.service';
import {
  ProcessingContext,
  ProcessingType,
  SubstituteValue
} from '../processor/processor.model';
import { C8YAPISubscription } from '../shared/mapping.model';

@Injectable({ providedIn: 'root' })
export class MappingService {
  constructor(
    private inventory: InventoryService,
    private brokerConfigurationService: BrokerConfigurationService,
    private jsonProcessorInbound: JSONProcessorInbound,
    private jsonProcessorOutbound: JSONProcessorOutbound,
    private sharedService: SharedService,
    private client: FetchClient
  ) {
    this.queriesUtil = new QueriesUtil();
  }

  queriesUtil: QueriesUtil;
  protected JSONATA = require('jsonata');

  private _mappingsInbound: Promise<Mapping[]>;
  private _mappingsOutbound: Promise<Mapping[]>;
  private reload$: Subject<void> = new Subject();

  async changeActivationMapping(parameter: any) {
    await this.brokerConfigurationService.runOperation(
      Operation.ACTIVATE_MAPPING,
      parameter
    );
  }

  resetCache() {
    this._mappingsInbound = undefined;
    this._mappingsOutbound = undefined;
  }

  async getMappings(direction: Direction): Promise<Mapping[]> {
    let mappings: Promise<Mapping[]>;
    if (
      (direction == Direction.INBOUND && !this._mappingsInbound) ||
      (direction == Direction.OUTBOUND && !this._mappingsOutbound)
    ) {
      const result: Mapping[] = [];
      const filter: object = {
        pageSize: 200,
        withTotalPages: true,
      };
      const query: any = {
        __and: [
          { 'd11r_mapping.direction': direction },
          { 'type': MAPPING_TYPE }
        ]
      };

    //   if (direction == Direction.INBOUND) {
    //     query = this.queriesUtil.addOrFilter(query, {
    //       __not: { __has: 'd11r_mapping.direction' }
    //     });
    //   }
    //   query = this.queriesUtil.addAndFilter(query, {
    //     type: { __has: 'd11r_mapping' }
    //   });

      const { data } = await this.inventory.listQuery(query, filter);

      data.forEach((m) =>
        result.push({
          ...m[MAPPING_FRAGMENT],
          id: m.id
        })
      );

      mappings = Promise.resolve(result);
      if (direction == Direction.INBOUND) {
        this._mappingsInbound = mappings;
      } else {
        this._mappingsOutbound = mappings;
      }
    } else {
      if (direction == Direction.INBOUND) {
        mappings = this._mappingsInbound;
      } else {
        mappings = this._mappingsOutbound;
      }
    }
    return mappings;
  }

  initializeCache(dir: Direction): void {
    if (dir == Direction.INBOUND) {
      this.jsonProcessorInbound.initializeCache();
    }
  }

  async updateSubscriptions(sub: C8YAPISubscription): Promise<any> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(sub),
        method: 'PUT'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const m = await data.text();
    return m;
  }

  async deleteSubscriptions(device: IIdentified): Promise<any> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_SUBSCRIPTION_ENDPOINT}/${device.id}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'DELETE'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const m = await data.text();
    return m;
  }

  async getSubscriptions(): Promise<C8YAPISubscription> {
    const feature = await this.sharedService.getFeatures();

    if (feature?.outputMappingEnabled) {
      const res: IFetchResponse = await this.client.fetch(
        `${BASE_URL}/${PATH_SUBSCRIPTIONS_ENDPOINT}`,
        {
          headers: {
            'content-type': 'application/json'
          },
          method: 'GET'
        }
      );
      const data = await res.json();
      return data;
    } else {
      return null;
    }
  }

  async saveMappings(mappings: Mapping[]): Promise<void> {
    mappings.forEach((m) => {
      this.inventory.update({
        d11r_mapping: m,
        id: m.id
      });
    });
    this.reload$.next();
  }

  async updateMapping(mapping: Mapping): Promise<Mapping> {
    const response = this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${mapping.id}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(mapping),
        method: 'PUT'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const m = await data.json();
    this.reload$.next();
    return m;
  }

  async deleteMapping(id: string): Promise<string> {
    // let result = this.inventory.delete(mapping.id)
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_MAPPING_ENDPOINT}/${id}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        method: 'DELETE'
      }
    );
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    this.reload$.next();
    return data.text();
  }

  async createMapping(mapping: Mapping): Promise<Mapping> {
    const response = this.client.fetch(`${BASE_URL}/${PATH_MAPPING_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json'
      },
      body: JSON.stringify(mapping),
      method: 'POST'
    });
    const data = await response;
    if (!data.ok) throw new Error(data.statusText)!;
    const m = await data.json();
    this.reload$.next();
    return m;
  }

  private initializeContext(
    mapping: Mapping,
    sendPayload: boolean
  ): ProcessingContext {
    const ctx: ProcessingContext = {
      mapping: mapping,
      topic: mapping.templateTopicSample,
      processingType: ProcessingType.UNDEFINED,
      cardinality: new Map<string, number>(),
      errors: [],
      mappingType: mapping.mappingType,
      postProcessingCache: new Map<string, SubstituteValue[]>(),
      sendPayload: sendPayload,
      requests: []
    };
    return ctx;
  }

  async testResult(
    mapping: Mapping,
    sendPayload: boolean
  ): Promise<ProcessingContext> {
    const context = this.initializeContext(mapping, sendPayload);
    if (mapping.direction == Direction.INBOUND) {
      this.jsonProcessorInbound.deserializePayload(context, mapping);
      await this.jsonProcessorInbound.extractFromSource(context);
      await this.jsonProcessorInbound.substituteInTargetAndSend(context);
    } else {
      this.jsonProcessorOutbound.deserializePayload(context, mapping);
      await this.jsonProcessorOutbound.extractFromSource(context);
      await this.jsonProcessorOutbound.substituteInTargetAndSend(context);
    }

    // The producing code (this may take some time)
    return context;
  }

  async evaluateExpression(json: JSON, path: string): Promise<JSON> {
    let result: any = '';
    if (path != undefined && path != '' && json != undefined) {
      const expression = this.JSONATA(path);
      result = expression.evaluate(json) as JSON;
    }
    return result;
  }
}
