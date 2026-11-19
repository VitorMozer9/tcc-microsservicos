package com.github.vitormozer9.pedido;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static com.github.vitormozer9.pedido.CatalogoHttpDeTeste.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PedidoHttpTest {
    private static final CatalogoHttpDeTeste catalogo = new CatalogoHttpDeTeste();
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int porta;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("produto.base-url", catalogo::url);
        registry.add("produto.timeout-leitura", () -> "3s");
    }

    @BeforeEach
    void preparar() {
        catalogo.chamadas.clear();
        catalogo.produtos.clear();
        catalogo.produtos.put(1L, new Resposta(200, produto(1, "Produto A", "25.00")));
        catalogo.produtos.put(2L, new Resposta(200, produto(2, "Produto B", "0.10")));
    }

    @AfterAll
    static void fechar() { catalogo.close(); }

    HttpResponse<String> enviar(String metodo, String caminho, String corpo) throws Exception {
        var uri = URI.create("http://localhost:" + porta + "/api/v1/pedidos" + caminho);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json")
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    JsonNode chamar(String metodo, String caminho, String corpo, int status) throws Exception {
        var r = enviar(metodo, caminho, corpo);
        assertThat(r.statusCode()).as("%s %s: %s", metodo, caminho, r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var body = json.readTree(r.body());
        if (status >= 400) assertThat(body.get("erro").stringValue()).isNotBlank();
        return body;
    }

    String item(long id, int qtd) {
        return "{\"produtoId\":%d,\"quantidade\":%d}".formatted(id, qtd);
    }

    String itens(String... itens) {
        return "{\"itens\":[" + String.join(",", itens) + "]}";
    }

    String novo(String... entradas) throws Exception {
        return "/" + chamar("POST", "", itens(entradas), 201).get("id").longValue();
    }

    void valor(JsonNode node, String campo, String esperado) {
        assertThat(node.get(campo).isNumber()).isTrue();
        assertThat(node.get(campo).decimalValue()).isEqualByComparingTo(esperado);
    }

    @Test
    void criaComVariosItensEConsultaSnapshotsLocais() throws Exception {
        var pedido = chamar("POST", "", itens(item(1, 2), item(2, 3)), 201);
        assertThat(pedido.propertyNames()).containsExactlyInAnyOrder("id", "status", "criadoEm", "itens", "total");
        assertThat(pedido.get("status").stringValue()).isEqualTo("ABERTO");
        assertThat(Instant.parse(pedido.get("criadoEm").stringValue())).isBeforeOrEqualTo(Instant.now());
        var primeiro = pedido.get("itens").get(0);
        assertThat(primeiro.propertyNames()).containsExactlyInAnyOrder("produtoId", "nomeProduto", "quantidade", "precoUnitario", "subtotal");
        assertThat(primeiro.get("produtoId").longValue()).isEqualTo(1);
        assertThat(primeiro.get("quantidade").intValue()).isEqualTo(2);
        assertThat(primeiro.get("nomeProduto").stringValue()).isEqualTo("Produto A");
        valor(primeiro, "precoUnitario", "25.00");
        valor(primeiro, "subtotal", "50.00");
        valor(pedido.get("itens").get(1), "subtotal", "0.30");
        valor(pedido, "total", "50.30");
        String caminho = "/" + pedido.get("id").longValue();
        assertThat(chamar("GET", caminho, null, 200)).isEqualTo(pedido);
        assertThat(catalogo.chamadas).containsExactly("GET /api/v1/produtos/1", "GET /api/v1/produtos/2");
    }

    @Test
    void gerenciaECancelaComCatalogoIndisponivel() throws Exception {
        String caminho = novo(item(1, 2));
        var adicionado = chamar("POST", caminho + "/itens", item(2, 3), 200);
        valor(adicionado, "total", "50.30");
        catalogo.produtos.put(2L, new Resposta(200, produto(2, "Nome novo", "90.00")));
        var alterado = chamar("PUT", caminho + "/itens/2", "{\"quantidade\":5}", 200);
        var historico = alterado.get("itens").get(1);
        assertThat(historico.get("nomeProduto").stringValue()).isEqualTo("Produto B");
        valor(historico, "precoUnitario", "0.10");
        valor(alterado, "total", "50.50");
        catalogo.produtos.replaceAll((id, r) -> new Resposta(503, "{}"));
        chamar("PUT", caminho + "/itens/1", "{\"quantidade\":4}", 200);
        valor(chamar("DELETE", caminho + "/itens/1", null, 200), "total", "0.50");
        chamar("DELETE", caminho + "/itens/2", null, 409);
        var cancelado = chamar("POST", caminho + "/cancelamento", null, 200);
        assertThat(cancelado.get("status").stringValue()).isEqualTo("CANCELADO");
        assertThat(cancelado.get("itens").get(0)).isEqualTo(historico);
        chamar("POST", caminho + "/cancelamento", null, 409);
        chamar("POST", caminho + "/itens", item(1, 1), 409);
        chamar("PUT", caminho + "/itens/2", "{\"quantidade\":3}", 409);
        chamar("DELETE", caminho + "/itens/2", null, 409);
        assertThat(chamar("GET", caminho, null, 200)).isEqualTo(cancelado);
        assertThat(chamar("GET", "", null, 200).values()).contains(cancelado);
        assertThat(catalogo.chamadas).containsExactly("GET /api/v1/produtos/1", "GET /api/v1/produtos/2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "{\"itens\":[]}", "{\"itens\":[null]}", "{\"itens\":[{}]}",
            "{\"itens\":[{\"produtoId\":0,\"quantidade\":1}]}", "{\"itens\":[{\"produtoId\":1,\"quantidade\":0}]}",
            "{\"itens\":[{\"produtoId\":1,\"quantidade\":1.5}]}"})
    void entradaInvalidaAntesDoHttp(String corpo) throws Exception {
        chamar("POST", "", corpo, 400);
        assertThat(catalogo.chamadas).isEmpty();
    }

    @Test
    void errosDeIdItemEProdutoDuplicado() throws Exception {
        chamar("POST", "", itens(item(1, 1), item(1, 2)), 409);
        chamar("GET", "/abc", null, 400);
        chamar("GET", "/0", null, 400);
        chamar("GET", "/9999999", null, 404);
        String caminho = novo(item(1, 1));
        chamar("POST", caminho + "/itens", item(1, 3), 409);
        chamar("PUT", caminho + "/itens/1", "{\"quantidade\":-1}", 400);
        chamar("PUT", caminho + "/itens/2", "{\"quantidade\":1}", 404);
        chamar("DELETE", caminho + "/itens/2", null, 404);
        assertThat(catalogo.chamadas).containsExactly("GET /api/v1/produtos/1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ausente", "inativo", "conexao", "servidor", "timeout", "ilegivel", "id", "nome", "preco"})
    void falhaDoCatalogoPreservaPedidoEImpedeGravacaoParcial(String caso) throws Exception {
        String caminho = novo(item(1, 2));
        var original = chamar("GET", caminho, null, 200);
        var liberar = new CountDownLatch(1);
        var falha = switch (caso) {
            case "ausente" -> new Resposta(404, "{}");
            case "inativo" -> new Resposta(200, "{\"id\":2,\"ativo\":false}");
            case "conexao" -> new Resposta(0, "");
            case "servidor" -> new Resposta(500, "{}");
            case "timeout" -> new Resposta(200, produto(2,"B","1.00"), new CountDownLatch(1), liberar);
            case "ilegivel" -> new Resposta(200, "{");
            case "id" -> new Resposta(200, produto(99,"B","2.00"));
            case "nome" -> new Resposta(200, produto(2," ","2.00"));
            case "preco" -> new Resposta(200, produto(2,"B","2.001"));
            default -> throw new AssertionError(caso);
        };
        catalogo.produtos.put(2L, falha);
        int status = List.of("ausente", "inativo").contains(caso) ? 404 : 503;
        int pedidos = jdbc.queryForObject("select count(*) from pedido", Integer.class);
        int linhas = jdbc.queryForObject("select count(*) from item_pedido", Integer.class);
        try {
            chamar("POST", "", itens(item(1, 1), item(2, 1)), status);
            chamar("POST", caminho + "/itens", item(2, 1), status);
        } finally {
            liberar.countDown();
        }
        assertThat(jdbc.queryForObject("select count(*) from pedido", Integer.class)).isEqualTo(pedidos);
        assertThat(jdbc.queryForObject("select count(*) from item_pedido", Integer.class)).isEqualTo(linhas);
        assertThat(chamar("GET", caminho, null, 200)).isEqualTo(original);
    }

    @Test
    void cancelamentoEnquantoInclusaoEsperaCatalogo() throws Exception {
        String caminho = novo(item(1, 5));
        var chegou = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        catalogo.produtos.put(2L, new Resposta(200, produto(2, "B", "1"), chegou, liberar));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var inclusao = pool.submit(() -> enviar("POST", caminho + "/itens", item(2, 3)));
            try {
                assertThat(chegou.await(5, TimeUnit.SECONDS)).isTrue();
                chamar("POST", caminho + "/cancelamento", null, 200);
            } finally {
                liberar.countDown();
            }
            assertThat(inclusao.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        }
        var finalizado = chamar("GET", caminho, null, 200);
        assertThat(finalizado.get("status").stringValue()).isEqualTo("CANCELADO");
        assertThat(finalizado.get("itens").size()).isOne();
    }

    @Test
    void inclusoesConcorrentesEExclusoesConcorrentesPreservamInvariantes() throws Exception {
        String caminho = novo(item(1,1));
        var chegaram = new CountDownLatch(2);
        var liberar = new CountDownLatch(1);
        catalogo.produtos.put(2L, new Resposta(200, produto(2, "B", "1"), chegaram, liberar));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> incluir = () -> enviar("POST", caminho + "/itens", item(2,1)).statusCode();
            var a = pool.submit(incluir);
            var b = pool.submit(incluir);
            try {
                assertThat(chegaram.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                liberar.countDown();
            }
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(chamar("GET", caminho, null, 200).get("itens").size()).isEqualTo(2);
            var inicio = new CyclicBarrier(2);
            var primeira = pool.submit(() -> { inicio.await(5, TimeUnit.SECONDS); return enviar("DELETE", caminho+"/itens/1", null).statusCode(); });
            var segunda = pool.submit(() -> { inicio.await(5, TimeUnit.SECONDS); return enviar("DELETE", caminho+"/itens/2", null).statusCode(); });
            assertThat(List.of(primeira.get(), segunda.get())).containsExactlyInAnyOrder(200, 409);
        }
        var restante = chamar("GET", caminho, null, 200);
        assertThat(restante.get("itens").size()).isOne();
        assertThat(restante.get("total")).isEqualTo(restante.get("itens").get(0).get("subtotal"));
    }
}
