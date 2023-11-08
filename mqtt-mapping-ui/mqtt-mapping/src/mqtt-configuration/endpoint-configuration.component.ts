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
import { Component, OnInit } from "@angular/core";
import { FormControl, FormGroup, Validators } from "@angular/forms";
import { EndpointConfigurationService } from "./endpoint-configuration.service";
import { AlertService, gettext } from "@c8y/ngx-components";
import { BsModalRef, BsModalService } from "ngx-bootstrap/modal";
import { TerminateEndpointConnectionModalComponent } from "./terminate/terminate-connection-modal.component";
import { MappingService } from "../mqtt-mapping/core/mapping.service";
import { from, Observable } from "rxjs";
import { map } from "rxjs/operators";
import {
  ConnectionConfiguration as ConnectionConfiguration,
  Feature,
  Operation,
  ServiceConfiguration,
  ServiceStatus,
  Status,
} from "../shared/mapping.model";
import packageJson from "../../package.json";

@Component({
  selector: "mapping-broker-configuration",
  templateUrl: "endpoint-configuration.component.html",
})
export class EndpointConfigurationComponent implements OnInit {
  version: string = packageJson.version;
  isEndpointConnected: boolean;
  isConnectionEnabled: boolean;
  isEndpointAgentCreated$: Observable<boolean>;
  monitorings$: Observable<ServiceStatus>;
  subscription: any;
  connectionForm: FormGroup;
  serviceForm: FormGroup;
  feature: Feature;
  connectorId: String;

  connectionConfiguration: ConnectionConfiguration = {
    mqttHost: "",
    mqttPort: 0,
    user: "",
    password: "",
    clientId: "",
    useTLS: false,
    enabled: false,
    useSelfSignedCertificate: false,
    fingerprintSelfSignedCertificate: "",
    nameCertificate: "",
  };
  serviceConfiguration: ServiceConfiguration = {
    logPayload: true,
    logSubstitution: true,
  };

  constructor(
    public bsModalService: BsModalService,
    public configurationService: EndpointConfigurationService,
    public mappingService: MappingService,
    public alertservice: AlertService
  ) {}

  async ngOnInit() {
    console.log("Running version", this.version);
    this.initForms();
    this.loadData();
    this.initializeMonitoringService();
    this.isEndpointAgentCreated$ = from(
      this.configurationService.initializeEndpointAgent()
    )
      // .pipe(map(agentId => agentId != null), tap(() => this.initializeMonitoringService()));
      .pipe(map((agentId) => agentId != null));
    this.feature = await this.configurationService.getFeatures();
  }

  private async initializeMonitoringService(): Promise<void> {
    this.subscription =
      await this.configurationService.subscribeMonitoringChannel();
    this.monitorings$ = this.configurationService.getCurrentServiceStatus();
    this.monitorings$.subscribe((status) => {
      this.isEndpointConnected = status.status === Status.CONNECTED;
      this.isConnectionEnabled =
        status.status === Status.ENABLED || status.status === Status.CONNECTED;
    });
  }

  async loadConnectionStatus(): Promise<void> {
    let status = await this.configurationService.getConnectionStatus();
    this.isEndpointConnected = status.status === Status.CONNECTED;
    this.isConnectionEnabled =
      status.status === Status.ENABLED || status.status === Status.CONNECTED;
    console.log("Retrieved status:", status, this.isEndpointConnected);
  }

  private initForms(): void {
    this.connectionForm = new FormGroup({
      mqttHost: new FormControl("", Validators.required),
      mqttPort: new FormControl("", Validators.required),
      user: new FormControl(""),
      password: new FormControl(""),
      clientId: new FormControl("", Validators.required),
      useTLS: new FormControl(""),
      useSelfSignedCertificate: new FormControl(""),
      nameCertificate: new FormControl(""),
    });
    this.serviceForm = new FormGroup({
      logPayload: new FormControl(""),
      logSubstitution: new FormControl(""),
    });
  }

  private async loadData(): Promise<void> {
    let conn = await this.configurationService.getConnectionConfiguration();
    let conf = await this.configurationService.getServiceConfiguration();
    console.log("Configuration:", conn, conf);
    if (conn) {
      this.connectionConfiguration = conn;
      this.isConnectionEnabled = conn.enabled;
    }

    if (conf) {
      this.serviceConfiguration = conf;
    }
  }

  async clickedConnect() {
    this.connectToEndpoint();
  }

  async clickedDisconnect() {
    this.showTerminateConnectionModal();
  }

  async clickedSaveConnectionConfiguration() {
    this.updateConnectionConfiguration();
  }

  async clickedSaveServiceConfiguration() {
    this.updateServiceConfiguration();
  }

  async clickedReconnect2NotificationEnpoint() {
    const response1 = await this.configurationService.runOperation(
      Operation.REFRESH_NOTFICATIONS_SUBSCRIPTIONS,
      { connectorId: this.connectorId }
    );
    console.log("Details reconnect2NotificationEnpoint", response1);
    if (response1.status === 201) {
      this.alertservice.success(gettext("Reconnect successful!"));
    } else {
      this.alertservice.danger(gettext("Failed to reconnect."));
    }
  }

  private async updateConnectionConfiguration() {
    let conn: ConnectionConfiguration = {
      ...this.connectionConfiguration,
      enabled: false,
    };
    const response =
      await this.configurationService.updateConnectionConfiguration(conn);
    if (response.status < 300) {
      this.alertservice.success(gettext("Update successful."));
    } else {
      this.alertservice.danger(gettext("Failed to update connection"));
    }
  }

  private async updateServiceConfiguration() {
    let conf: ServiceConfiguration = {
      ...this.serviceConfiguration,
    };
    const response = await this.configurationService.updateServiceConfiguration(
      conf
    );
    if (response.status < 300) {
      this.alertservice.success(gettext("Update successful"));
    } else {
      this.alertservice.danger(
        gettext("Failed to update service configuration")
      );
    }
  }

  private async connectToEndpoint() {
    const response1 = await this.configurationService.runOperation(
      Operation.CONNECT,
      { connectorId: this.connectorId }
    );
    //const response2 = await this.mappingService.activateMappings();
    //console.log("Details connectToEndpoint", response1, response2)
    console.log("Details connectToEndpoint", response1);
    if (response1.status === 201) {
      // if (response1.status === 201 && response2.status === 201) {
      this.alertservice.success(gettext("Connection successful"));
    } else {
      this.alertservice.danger(gettext("Failed to establish connection"));
    }
  }

  private showTerminateConnectionModal() {
    const terminateExistingConnectionModalRef: BsModalRef =
      this.bsModalService.show(TerminateEndpointConnectionModalComponent, {});
    terminateExistingConnectionModalRef.content.closeSubject.subscribe(
      async (isTerminateConnection: boolean) => {
        console.log("Termination result:", isTerminateConnection);
        if (!isTerminateConnection) {
        } else {
          await this.disconnectFromEndpoint();
        }
        terminateExistingConnectionModalRef.hide();
      }
    );
  }

  private async disconnectFromEndpoint() {
    const res = await this.configurationService.runOperation(
      Operation.DISCONNECT,
      { connectorId: this.connectorId }
    );
    console.log("Details disconnectFromMQTT", res);
    if (res.status < 300) {
      this.alertservice.success(gettext("Successfully disconnected"));
    } else {
      this.alertservice.danger(gettext("Failed to disconnect"));
    }
  }

  public async resetStatusMapping() {
    const res = await this.configurationService.runOperation(
      Operation.RESET_STATUS_MAPPING
    );
    if (res.status < 300) {
      this.alertservice.success(gettext("Successfully rreset"));
    } else {
      this.alertservice.danger(gettext("Failed to rest statistic."));
    }
  }

  ngOnDestroy(): void {
    console.log("Stop subscription");
    this.configurationService.unsubscribeFromMonitoringChannel(
      this.subscription
    );
  }
}