package com.alipay.sofa.registry.client.test;

import com.alipay.sofa.registry.client.api.Publisher;
import com.alipay.sofa.registry.client.api.RegistryClientConfig;
import com.alipay.sofa.registry.client.api.registration.PublisherRegistration;
import com.alipay.sofa.registry.client.provider.DefaultRegistryClient;
import com.alipay.sofa.registry.client.provider.DefaultRegistryClientConfigBuilder;

import java.io.IOException;

public class PublisherTest {

    public static void main(String[] args) {
        // 构建客户端实例
        RegistryClientConfig config = DefaultRegistryClientConfigBuilder
                .start()
                .setRegistryEndpoint("127.0.0.1")
                .setRegistryEndpointPort(9603)
                .build();
        DefaultRegistryClient registryClient = new DefaultRegistryClient(config);
        registryClient.init();

        // 构造发布者注册表
        String dataId = "com.alipay.test.demo.service:1.0@DEFAULT";
        PublisherRegistration registration = new PublisherRegistration(dataId);
        registration.setAppName("test_appName");
        registration.setGroup("group");

        // 将注册表注册进客户端并发布数据
        Publisher publisher = registryClient.register(registration, "10.10.1.1:12200?xx=yy");
        publisher.republish("10.10.1.1:12200?xx=yy");

        try {
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
