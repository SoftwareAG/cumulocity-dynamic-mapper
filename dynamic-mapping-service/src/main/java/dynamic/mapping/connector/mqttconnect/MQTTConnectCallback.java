package dynamic.mapping.connector.mqttconnect;

import com.cumulocity.mqtt.service.client.MqttMessageListener;
import com.cumulocity.mqtt.service.client.model.MqttMessage;
import dynamic.mapping.connector.core.callback.ConnectorMessage;
import dynamic.mapping.connector.core.callback.GenericMessageCallback;

public class MQTTConnectCallback implements MqttMessageListener {

    GenericMessageCallback genericMessageCallback;
    String tenant;
    String connectorIdent;

    String topic;
    MQTTConnectCallback(GenericMessageCallback callback, String tenant, String connectorIdent) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorIdent = connectorIdent;}
    @Override
    public void onMessage(MqttMessage mqttMessage) {
        ConnectorMessage connectorMessage = new ConnectorMessage();
        connectorMessage.setPayload(mqttMessage.getPayload());
        connectorMessage.setTenant(tenant);
        connectorMessage.setSendPayload(true);
        connectorMessage.setTopic(mqttMessage.getMetadata().getTopic());
        connectorMessage.setConnectorIdent(connectorIdent);
        try {
            genericMessageCallback.onMessage(connectorMessage);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        genericMessageCallback.onClose(null,throwable);
    }
}
