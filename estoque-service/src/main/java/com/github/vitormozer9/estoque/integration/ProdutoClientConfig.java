package com.github.vitormozer9.estoque.integration;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class ProdutoClientConfig {
    @Bean
    RestClient produtoRestClient(RestClient.Builder builder,
            @Value("${produto.base-url}") String baseUrl,
            @Value("${produto.timeout-conexao}") Duration conexao,
            @Value("${produto.timeout-leitura}") Duration leitura) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(conexao);
        factory.setReadTimeout(leitura);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
