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
import { FetchClient, IEvent, IFetchResponse } from '@c8y/client';
import {
  BASE_URL,
  CONNECTOR_FRAGMENT,
  ConnectorConfiguration,
  ConnectorSpecification,
  ConnectorStatus,
  ConnectorStatusEvent,
  PATH_CONFIGURATION_CONNECTION_ENDPOINT,
  PATH_STATUS_CONNECTORS_ENDPOINT,
  SharedService,
  StatusEventTypes
} from '.';

import {
  BehaviorSubject,
  combineLatest,
  forkJoin,
  from,
  Observable,
  Subject
} from 'rxjs';
import { map, switchMap } from 'rxjs/operators';
import { Realtime } from '@c8y/ngx-components/api';

@Injectable({ providedIn: 'root' })
export class ConnectorConfigurationService {
  constructor(
    private client: FetchClient,
    private sharedService: SharedService
  ) {
    this.initConnectorConfigurations();
    this.realtime = new Realtime(this.client);
    // console.log("Constructor:BrokerConfigurationService");
  }

  private _connectorConfigurations: ConnectorConfiguration[];
  private _connectorSpecifications: ConnectorSpecification[];

  private triggerConfigurations$: Subject<string> = new Subject();
  private incomingRealtime$: Subject<IEvent> = new Subject();
  private connectorConfigurations$: Observable<ConnectorConfiguration[]>;

  private _agentId: string;
  private realtime: Realtime;
  private subscriptionEvents: any;

  getConnectorConfigurationsLive(): Observable<ConnectorConfiguration[]> {
    return this.connectorConfigurations$;
  }

  resetCache() {
    // console.log('Calling: BrokerConfigurationService.resetCache()');
    this._connectorConfigurations = [];
    this._connectorSpecifications = undefined;
  }

  startConnectorConfigurations() {
    this.triggerConfigurations$.next('');
    this.incomingRealtime$.next({} as any);
	this.startConnectorStatusSubscriptions();
  }

  reloadConnectorConfigurations() {
    this.triggerConfigurations$.next('');
  }

  stopConnectorConfigurations() {
    this.realtime.unsubscribe(this.subscriptionEvents);
  }

  refreshConnectorConfigurations() {
    this.triggerConfigurations$.next('');
  }

  initConnectorConfigurations() {
    // console.log(
    //   'Calling BrokerConfigurationService.initConnectorConfigurations()'
    // );
    const connectorConfig$ = this.triggerConfigurations$.pipe(
      // tap(() => console.log('New triggerConfigurations!')),
      switchMap(() => {
        const observableConfigurations = from(
          this.getConnectorConfigurations()
        );
        const observableStatus = from(this.getConnectorStatus());
        return forkJoin([observableConfigurations, observableStatus]);
      }),
      map((vars) => {
        const [configurations, connectorStatus] = vars;
        configurations.forEach((cc) => {
          const status = connectorStatus[cc.ident]
            ? connectorStatus[cc.ident].status
            : ConnectorStatus.UNKNOWN;
          if (!cc['status$']) {
            cc['status$'] = new BehaviorSubject<string>(status);
          } else {
            cc['status$'].next(status);
          }
        });
        return configurations;
      })
    );
    this.connectorConfigurations$ = combineLatest([
      connectorConfig$,
      this.incomingRealtime$
    ]).pipe(
      map((vars) => {
        const [configurations, payload] = vars;
        if (payload?.type == StatusEventTypes.STATUS_CONNECTOR_EVENT_TYPE) {
          const statusLog: ConnectorStatusEvent = payload[CONNECTOR_FRAGMENT];
          configurations.forEach((cc) => {
            if (statusLog['connectorIdent'] == cc.ident) {
              if (!cc['status$']) {
                cc['status$'] = new BehaviorSubject<string>(statusLog.status);
              } else {
                cc['status$'].next(statusLog.status);
              }
            }
          });
        }
        return configurations;
      })
    );
  }

  async getConnectorSpecifications(): Promise<ConnectorSpecification[]> {
    if (!this._connectorSpecifications) {
      const response = await this.client.fetch(
        `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/specifications`,
        {
          headers: {
            accept: 'application/json'
          },
          method: 'GET'
        }
      );
      this._connectorSpecifications = await response.json();
    }
    return this._connectorSpecifications;
  }

  async getConnectorStatus(): Promise<ConnectorStatus> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_STATUS_CONNECTORS_ENDPOINT}`,
      {
        method: 'GET'
      }
    );
    const result = await response.json();
    return result;
  }

  async updateConnectorConfiguration(
    configuration: ConnectorConfiguration
  ): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${configuration.ident}`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(configuration),
        method: 'PUT'
      }
    );
  }

  async createConnectorConfiguration(
    configuration: ConnectorConfiguration
  ): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance`,
      {
        headers: {
          'content-type': 'application/json'
        },
        body: JSON.stringify(configuration),
        method: 'POST'
      }
    );
  }

  async deleteConnectorConfiguration(ident: string): Promise<IFetchResponse> {
    return this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instance/${ident}`,
      {
        headers: {
          accept: 'application/json',
          'content-type': 'application/json'
        },
        method: 'DELETE'
      }
    );
  }

  async getConnectorConfigurations(): Promise<ConnectorConfiguration[]> {
    const response = await this.client.fetch(
      `${BASE_URL}/${PATH_CONFIGURATION_CONNECTION_ENDPOINT}/instances`,
      {
        headers: {
          accept: 'application/json'
        },
        method: 'GET'
      }
    );
    this._connectorConfigurations = await response.json();

    return this._connectorConfigurations;
  }

  async startConnectorStatusSubscriptions(): Promise<void> {
    if (!this._agentId) {
      this._agentId = await this.sharedService.getDynamicMappingServiceAgent();
    }
    // console.log('Started subscriptions:', this._agentId);

    // subscribe to event stream
    this.subscriptionEvents = this.realtime.subscribe(
      `/events/${this._agentId}`,
      this.updateConnectorStatus
    );
  }

  private updateConnectorStatus = async (p: object) => {
    const payload = p['data']['data'];
    this.incomingRealtime$.next(payload);
  };
}
