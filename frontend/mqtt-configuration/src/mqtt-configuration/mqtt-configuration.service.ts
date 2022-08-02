import { Injectable } from '@angular/core';
import { FetchClient, IFetchResponse } from '@c8y/client';
import { MQTTAuthentication } from '../mqtt-configuration.model';

@Injectable({ providedIn: 'root' })
export class MQTTConfigurationService {
  private readonly PATH_CONNECT_ENDPOINT = 'connection';

  private readonly PATH_STATUS_ENDPOINT = 'status';

  private readonly BASE_URL = 'service/generic-mqtt-agent';

  private readonly STATUS_READY = 'READY';

  constructor(private client: FetchClient) {}

  updateConnectionDetails(mqttConfiguration: MQTTAuthentication): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_CONNECT_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify(mqttConfiguration),
      method: 'POST',
    });
  }

  connect(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_CONNECT_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      method: 'PUT',
    });
  }

  disconnect(): Promise<IFetchResponse> {
    return this.client.fetch(`${this.BASE_URL}/${this.PATH_CONNECT_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      method: 'DELETE',
    });
  }

  async getConnectionDetails(): Promise<MQTTAuthentication> {
    const response = await this.client.fetch(`${this.BASE_URL}/${this.PATH_CONNECT_ENDPOINT}`, {
      headers: {
        accept: 'application/json',
      },
      method: 'GET',
    });

    if (response.status != 200) {
      return undefined;
    }

    return (await response.json()) as MQTTAuthentication;
  }

  async getConnectionStatus(): Promise<string> {
    const response = await this.client.fetch(`${this.BASE_URL}/${this.PATH_STATUS_ENDPOINT}`, {
      method: 'GET',
    });
    const { status } = await response.json();
    return status;
  }
}
