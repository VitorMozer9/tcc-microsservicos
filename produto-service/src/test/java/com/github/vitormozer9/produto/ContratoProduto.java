package com.github.vitormozer9.produto;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Cenários comuns às três arquiteturas. A subclasse fornece a URL da coleção. */
abstract class ContratoProduto {
    private final HttpClient cliente = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    abstract String urlProdutos();

    HttpResponse<String> enviar(String metodo, String caminho, String corpo) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(urlProdutos() + caminho));
        if (corpo != null) {
            builder.header("Content-Type", "application/json");
        }
        return cliente.send(builder.method(metodo, corpo == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode resposta(String metodo, String caminho, String corpo, int status) throws Exception {
        var retorno = enviar(metodo, caminho, corpo);
        assertThat(retorno.statusCode()).as("%s %s: %s", metodo, caminho, retorno.body()).isEqualTo(status);
        assertThat(retorno.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        return json.readTree(retorno.body());
    }

    String dados(String nome, String descricao, String preco) {
        return json.createObjectNode().put("nome", nome).put("descricao", descricao)
                .put("preco", new java.math.BigDecimal(preco)).toString();
    }

    long cadastrar() throws Exception {
        return resposta("POST", "", dados("Produto", "Descrição", "25.50"), 201).get("id").longValue();
    }

    private void conferirProduto(JsonNode produto, String nome, String descricao, String preco) {
        assertThat(produto.propertyNames()).containsExactlyInAnyOrder("id", "nome", "descricao", "preco", "ativo");
        assertThat(produto.get("id").isIntegralNumber()).isTrue();
        assertThat(produto.get("id").longValue()).isPositive();
        assertThat(produto.get("nome").stringValue()).isEqualTo(nome);
        assertThat(produto.get("descricao").stringValue()).isEqualTo(descricao);
        assertThat(produto.get("ativo").isBoolean()).isTrue();
        assertThat(produto.get("ativo").booleanValue()).isTrue();
        assertThat(produto.get("preco").isNumber()).isTrue();
        assertThat(produto.get("preco").decimalValue()).isEqualByComparingTo(preco);
    }

    @Test
    void cadastroConsultaEAtualizacaoMantemContrato() throws Exception {
        var criado = resposta("POST", "", dados("Café", "Pacote", "25.50"), 201);
        conferirProduto(criado, "Café", "Pacote", "25.50");
        String caminho = "/" + criado.get("id").longValue();
        conferirProduto(resposta("GET", caminho, null, 200), "Café", "Pacote", "25.50");
        var atualizado = resposta("PUT", caminho, dados("Café novo", "Caixa", "0.01"), 200);
        assertThat(atualizado.get("id")).isEqualTo(criado.get("id"));
        conferirProduto(atualizado, "Café novo", "Caixa", "0.01");
        conferirProduto(resposta("GET", caminho, null, 200), "Café novo", "Caixa", "0.01");
    }

    @Test
    void listagemIncluiAtivoEOmiteDesativado() throws Exception {
        long ativo = cadastrar();
        long removido = cadastrar();
        var exclusao = enviar("DELETE", "/" + removido, null);
        assertThat(exclusao.statusCode()).isEqualTo(204);
        assertThat(exclusao.body()).isEmpty();
        var lista = resposta("GET", "", null, 200);
        assertThat(lista.isArray()).isTrue();
        assertThat(lista.values()).allMatch(p -> p.get("ativo").booleanValue())
                .extracting(p -> p.get("id").longValue()).contains(ativo).doesNotContain(removido);
        conferirAusente(removido);
    }

    @Test
    void operacoesComIdInexistenteRetornam404() throws Exception {
        conferirAusente(Long.MAX_VALUE);
    }

    private void conferirAusente(long id) throws Exception {
        for (String metodo : new String[]{"GET", "PUT", "DELETE"}) {
            String corpo = metodo.equals("PUT") ? dados("Produto", "", "1") : null;
            var erro = resposta(metodo, "/" + id, corpo, 404);
            assertThat(erro.get("erro").stringValue()).isNotBlank();
        }
    }

    @Test
    void aceitaLimitesDosCamposEDescricaoOmitida() throws Exception {
        resposta("POST", "", dados("N".repeat(120), "D".repeat(500), "9999999999.99"), 201);
        var criado = resposta("POST", "", "{\"nome\":\"Simples\",\"preco\":1}", 201);
        assertThat(criado.get("descricao").isNull()).isTrue();
    }

    static Stream<String> entradasInvalidas() {
        return Stream.of("{}", "{\"preco\":1}", "{\"nome\":\"A\"}",
                "{\"nome\":\"\",\"preco\":1}", "{\"nome\":\"   \",\"preco\":1}",
                "{\"nome\":\"" + "N".repeat(121) + "\",\"preco\":1}",
                "{\"nome\":\"A\",\"descricao\":\"" + "D".repeat(501) + "\",\"preco\":1}",
                "{\"nome\":\"A\",\"preco\":0}", "{\"nome\":\"A\",\"preco\":-1}",
                "{\"nome\":\"A\",\"preco\":0.001}", "{");
    }

    @ParameterizedTest
    @MethodSource("entradasInvalidas")
    void rejeitaEntradaInvalidaEmCadastroEAtualizacao(String corpo) throws Exception {
        String caminho = "/" + cadastrar();
        assertThat(enviar("POST", "", corpo).statusCode()).isEqualTo(400);
        assertThat(enviar("PUT", caminho, corpo).statusCode()).isEqualTo(400);
        conferirProduto(resposta("GET", caminho, null, 200), "Produto", "Descrição", "25.50");
    }
}
