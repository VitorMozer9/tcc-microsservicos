package com.github.vitormozer9.estoque;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.resource.jdbc.spi.StatementInspector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.github.vitormozer9.estoque.EstoqueHttpTest$SincronizarUpdates")
@ActiveProfiles("test")
class EstoqueHttpTest {
    private static final HttpServer catalogo = iniciarCatalogo();
    private static final AtomicInteger chamadas = new AtomicInteger();
    private static final ConcurrentMap<Long, String> cenarios = new ConcurrentHashMap<>();
    private static final CountDownLatch liberarTimeout = new CountDownLatch(1);

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registry) {
        registry.add("produto.base-url", () -> "http://localhost:" + catalogo.getAddress().getPort());
        registry.add("produto.timeout-leitura", () -> "1s");
    }

    private static HttpServer iniciarCatalogo() {
        try {
            var servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            servidor.createContext("/", exchange -> {
                try (exchange) {
                    chamadas.incrementAndGet();
                    String path = exchange.getRequestURI().toString();
                    if (!exchange.getRequestMethod().equals("GET") || !path.matches("/api/v1/produtos/[0-9]+")) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                    String cenario = cenarios.getOrDefault(id, "ativo");
                    int status = 200;
                    String corpo = """
                            {"id":%d,"nome":"Produto","descricao":null,"preco":1.00,"ativo":%b}
                            """.formatted(id, !cenario.equals("inativo"));
                    switch (cenario) {
                        case "ausente" -> status = 404;
                        case "falha" -> status = 500;
                        case "desconectado" -> { return; }
                        case "invalido" -> corpo = "{}";
                        case "ilegivel" -> corpo = "{";
                        case "lento" -> {
                            try {
                                liberarTimeout.await(8, TimeUnit.SECONDS);
                            } catch (InterruptedException erro) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    var bytes = corpo.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            servidor.start();
            return servidor;
        } catch (IOException erro) {
            throw new ExceptionInInitializerError(erro);
        }
    }

    @AfterAll
    static void encerrar() {
        liberarTimeout.countDown();
        catalogo.stop(0);
    }

    @LocalServerPort int porta;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> enviar(String caminho, String corpo) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/api/v1/estoques" + caminho));
        if (corpo != null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(corpo));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode chamar(String caminho, String corpo, int status) throws Exception {
        var response = enviar(caminho, corpo);
        assertThat(response.statusCode()).as("%s: %s", caminho, response.body()).isEqualTo(status);
        var payload = json.readTree(response.body());
        if (status >= 400) {
            assertThat(payload.get("erro").stringValue()).isNotBlank();
        }
        return payload;
    }

    private JsonNode cadastrar(long id, int inicial, int status) throws Exception {
        return chamar("", "{\"produtoId\":%d,\"quantidadeInicial\":%d}".formatted(id, inicial), status);
    }

    private void saldo(JsonNode resposta, long id, int quantidade) {
        assertThat(resposta.propertyNames()).containsExactlyInAnyOrder("produtoId", "quantidadeDisponivel");
        assertThat(resposta.get("produtoId").longValue()).isEqualTo(id);
        assertThat(resposta.get("quantidadeDisponivel").intValue()).isEqualTo(quantidade);
    }

    private JsonNode mover(long id, String tipo, int qtd, int status) throws Exception {
        return chamar("/" + id + "/movimentacoes", "{\"tipo\":\"%s\",\"quantidade\":%d}".formatted(tipo, qtd), status);
    }

    @Test
    void consultaEMovimentacoesSaoLocaisMesmoAposDesativacao() throws Exception {
        int antes = chamadas.get();
        saldo(cadastrar(1, 10, 201), 1, 10);
        assertThat(chamadas.get()).isEqualTo(antes + 1);
        cenarios.put(1L, "ausente"); // Produto desativado retorna 404.
        saldo(chamar("/1", null, 200), 1, 10);
        saldo(mover(1, "ENTRADA", 5, 200), 1, 15);
        saldo(mover(1, "SAIDA", 3, 200), 1, 12);
        mover(1, "SAIDA", 13, 409);
        saldo(chamar("/1", null, 200), 1, 12);
        assertThat(jdbc.queryForObject("select quantidade_disponivel from estoque where produto_id=1", Integer.class))
                .isEqualTo(12);
        assertThat(chamadas.get()).isEqualTo(antes + 1);
    }

    @Test
    void cadastroComZeroEDuplicidade() throws Exception {
        saldo(cadastrar(2, 0, 201), 2, 0);
        cadastrar(2, 10, 409);
        saldo(chamar("/2", null, 200), 2, 0);
    }

    @Test
    void cadastroConcorrentePreservaUmUnicoSaldo() throws Exception {
        var largada = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> cadastro = () -> {
                largada.await(5, TimeUnit.SECONDS);
                return enviar("", "{\"produtoId\":88,\"quantidadeInicial\":4}").statusCode();
            };
            var a = pool.submit(cadastro);
            var b = pool.submit(cadastro);
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        saldo(chamar("/88", null, 200), 88, 4);
    }

    @Test
    void saidasQueLeramAMesmaVersaoResultamEmUmConflito() throws Exception {
        cadastrar(89, 10, 201);
        SincronizarUpdates.barreira = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<HttpResponse<String>> saida = () ->
                    enviar("/89/movimentacoes", "{\"tipo\":\"SAIDA\",\"quantidade\":7}");
            var a = pool.submit(saida);
            var b = pool.submit(saida);
            var respostas = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(respostas).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            var conflito = respostas.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
            assertThat(json.readTree(conflito.body()).get("erro").stringValue()).contains("outra operação");
        } finally {
            SincronizarUpdates.barreira = null;
        }
        saldo(chamar("/89", null, 200), 89, 3);
        assertThat(jdbc.queryForObject("select quantidade_disponivel from estoque where produto_id=89", Integer.class))
                .isEqualTo(3);
    }

    public static class SincronizarUpdates implements StatementInspector {
        static volatile CyclicBarrier barreira;

        @Override
        public String inspect(String sql) {
            var ativa = barreira;
            if (ativa != null && sql.startsWith("update estoque ")) {
                // Ambas as transações já leram o saldo e a versão antes de emitir o UPDATE.
                try {
                    ativa.await(8, TimeUnit.SECONDS);
                } catch (Exception erro) {
                    throw new IllegalStateException("As duas transações não chegaram ao UPDATE.", erro);
                }
            }
            return sql;
        }
    }

    @Test
    void rejeitaCadastroInvalidoAntesDeConsultarProduto() throws Exception {
        int antes = chamadas.get();
        cadastrar(3, -1, 400);
        cadastrar(0, 10, 400);
        chamar("", "{\"produtoId\":3}", 400);
        chamar("/3", null, 404);
        mover(3, "ENTRADA", 1, 404);
        assertThat(chamadas.get()).isEqualTo(antes);
    }

    @Test
    void movimentacoesInvalidasPreservamSaldo() throws Exception {
        cadastrar(4, 10, 201);
        mover(4, "ENTRADA", 0, 400);
        mover(4, "SAIDA", -1, 400);
        mover(4, "INVALIDO", 2, 400);
        chamar("/4/movimentacoes", "{\"tipo\":0,\"quantidade\":2}", 400);
        chamar("/4/movimentacoes", "{\"tipo\":\"SAIDA\",\"quantidade\":1.5}", 400);
        saldo(chamar("/4", null, 200), 4, 10);
    }

    @ParameterizedTest
    @CsvSource({"10,ausente,404", "11,inativo,404", "12,desconectado,503", "13,falha,503",
            "14,lento,503", "15,invalido,503", "16,ilegivel,503"})
    void falhaAoConsultarProdutoImpedeCadastro(long id, String cenario, int status) throws Exception {
        cenarios.put(id, cenario);
        try {
            cadastrar(id, 10, status);
        } finally {
            if (cenario.equals("lento")) liberarTimeout.countDown();
        }
        chamar("/" + id, null, 404);
    }
}
