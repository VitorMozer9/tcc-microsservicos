package com.github.vitormozer9.pedido.integration;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import com.github.vitormozer9.pedido.entity.ProdutoNoPedido;
import com.github.vitormozer9.pedido.exception.ProdutoIndisponivelException;
import com.github.vitormozer9.pedido.exception.RecursoNaoEncontradoException;

@Component
public class ProdutoClient {
    private final RestClient http;
    private final ObjectReader leitor;

    public ProdutoClient(RestClient http, ObjectMapper mapper) {
        this.http = http;
        this.leitor = mapper.readerFor(JsonNode.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public ProdutoNoPedido buscarAtivo(Long id) {
        JsonNode produto;
        try {
            byte[] corpo = http.get().uri("/api/v1/produtos/{id}", id).retrieve().body(byte[].class);
            if (corpo == null) throw respostaInvalida();
            produto = leitor.readTree(corpo);
        } catch (HttpClientErrorException.NotFound erro) {
            throw ausente(id);
        } catch (RestClientException | JacksonException erro) {
            throw new ProdutoIndisponivelException(erro);
        }

        if (produto == null || !produto.path("id").isIntegralNumber()
                || !produto.path("id").canConvertToLong() || produto.path("id").longValue() != id
                || !produto.path("ativo").isBoolean()) {
            throw respostaInvalida();
        }
        if (!produto.get("ativo").booleanValue()) throw ausente(id);
        var nome = produto.path("nome");
        var valor = produto.path("preco");
        if (!nome.isString() || nome.stringValue().isBlank() || nome.stringValue().length() > 120 || !valor.isNumber()) {
            throw respostaInvalida();
        }
        var preco = valor.decimalValue();
        if (preco.signum() <= 0 || preco.scale() > 2 || (long) preco.precision() - preco.scale() > 10) {
            throw respostaInvalida();
        }
        return new ProdutoNoPedido(id, nome.stringValue(), preco.setScale(2));
    }

    private RecursoNaoEncontradoException ausente(Long id) {
        return new RecursoNaoEncontradoException("Produto ativo não encontrado: " + id);
    }

    private ProdutoIndisponivelException respostaInvalida() {
        return new ProdutoIndisponivelException(new IllegalStateException("Resposta inválida do catálogo."));
    }
}
