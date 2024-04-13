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

package dynamic.mapping;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import javax.net.ssl.TrustManagerFactory;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;

import dynamic.mapping.processor.extension.internal.InternalCustomAlarmOuter;
import dynamic.mapping.processor.extension.internal.InternalCustomAlarmOuter.InternalCustomAlarm;
import dynamic.mapping.processor.processor.fixed.StaticCustomMeasurementOuter;
import dynamic.mapping.processor.processor.fixed.StaticCustomMeasurementOuter.StaticCustomMeasurement;

public class ProtobufPahoClient {
    Mqtt3BlockingClient testClient;
    static String broker_host = System.getenv("broker_host");
    static Integer broker_port = Integer.valueOf(System.getenv("broker_port"));
    static String client_id = System.getenv("client_id");
    static String broker_username = System.getenv("broker_username");
    static String broker_password = System.getenv("broker_password");

    public ProtobufPahoClient(Mqtt3BlockingClient sampleClient) {
        testClient = sampleClient;
    }

    public static TrustManagerFactory buildTrustManagerFactoryFromJKS(
            final Path truststorePath,
            final String password) throws Exception {

        final char[] checkedPassword = password.toCharArray();
        final TrustManagerFactory trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        final KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(new FileInputStream(truststorePath.toFile()), checkedPassword);
        trustManagerFactory.init(ks);
        return trustManagerFactory;
    }

    public static void main(String[] args) throws KeyStoreException, NoSuchAlgorithmException, CertificateException,
            FileNotFoundException, IOException {

        System.out.println("Connecting with JKS tm, " + System.getProperties()
        .getProperty("java.home"));

        KeyStore keyStore = KeyStore.getInstance("JKS");
        // load default jvm keystore
        keyStore.load(new FileInputStream(
                System.getProperties()
                        .getProperty("java.home") + File.separator
                        + "lib" + File.separator + "security" + File.separator
                        + "cacerts"),
                "changeit".toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());

        tmf.init(keyStore);
        Mqtt3SimpleAuth simpleAuth = Mqtt3SimpleAuth.builder().username(broker_username)
                .password(broker_password.getBytes()).build();
        Mqtt3BlockingClient sampleClient = Mqtt3Client.builder()
                .serverHost(broker_host)
                .serverPort(broker_port)
                .identifier(client_id)
                .simpleAuth(simpleAuth)
                .sslWithDefaultConfig()
                // .sslConfig()
                // .trustManagerFactory(tmf)
                // .hostnameVerifier((hostname, session) -> true)
                // .applySslConfig()
                .buildBlocking();
        ProtobufPahoClient client = new ProtobufPahoClient(sampleClient);
        client.testClient.connect();
        client.testSendMeasurement();
        // client.testSendAlarm();
        client.testClient.disconnect();
    }

    private void testSendMeasurement() {

        String topic = "protobuf/measurement";
        System.out.println("Connecting to broker: ssl://" + broker_host + ":" + broker_port);


        System.out.println("Publishing message: :::");

        StaticCustomMeasurementOuter.StaticCustomMeasurement proto = StaticCustomMeasurement.newBuilder()
                .setExternalIdType("c8y_Serial")
                .setExternalId("berlin_01")
                .setUnit("C")
                .setMeasurementType("c8y_GenericMeasurement")
                .setValue(99.7F)
                .build();

        Mqtt3AsyncClient sampleClientAsync = testClient.toAsync();
        sampleClientAsync.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(proto.toByteArray()).send();

        System.out.println("Message published");
        System.out.println("Disconnected");

    }

    private void testSendAlarm() {

        String topic = "protobuf/alarm";
        System.out.println("Connecting to broker: ssl://" + broker_host + ":" + broker_port);
        System.out.println("Publishing message: :::");

        InternalCustomAlarmOuter.InternalCustomAlarm proto = InternalCustomAlarm.newBuilder()
                .setExternalIdType("c8y_Serial")
                .setExternalId("berlin_01")
                .setTxt("Dummy Text")
                .setTimestamp(System.currentTimeMillis())
                .setAlarmType("c8y_ProtobufAlarmType")
                .build();
        Mqtt3AsyncClient sampleClientAsync = testClient.toAsync();
        sampleClientAsync.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(proto.toByteArray()).send();

        System.out.println("Message published");
        System.out.println("Disconnected");
    }

}
