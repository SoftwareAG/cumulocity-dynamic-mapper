package mqtt.mapping.connector.mqttconnect;

import com.cumulocity.mqtt.connect.client.MqttMessageListener;
import com.cumulocity.mqtt.connect.client.model.MqttMessage;
import mqtt.mapping.connector.core.callback.ConnectorMessage;
import mqtt.mapping.connector.core.callback.GenericMessageCallback;

public class MQTTConnectCallback implements MqttMessageListener {

    GenericMessageCallback genericMessageCallback;
    String tenant;
    String connectorId;
    String topic;

    MQTTConnectCallback(GenericMessageCallback callback, String tenant, String connectorId, String topic) {
        this.genericMessageCallback = callback;
        this.tenant = tenant;
        this.connectorId = connectorId;
        this.topic = topic;
    }
    @Override
    public void onMessage(MqttMessage mqttMessage)  {
        ConnectorMessage connectorMessage = new ConnectorMessage();
        connectorMessage.setPayload(mqttMessage.getPayload());
        try {
            genericMessageCallback.onMessage(topic,connectorMessage);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void onError(Throwable throwable) {
        genericMessageCallback.onClose(null,throwable);
    }
}
