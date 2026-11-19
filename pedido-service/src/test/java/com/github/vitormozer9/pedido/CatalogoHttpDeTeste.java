package com.github.vitormozer9.pedido;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;

final class CatalogoHttpDeTeste implements AutoCloseable {
    private final HttpServer servidor;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final List<String> chamadas = new CopyOnWriteArrayList<>();
    final ConcurrentMap<Long, Resposta> produtos = new ConcurrentHashMap<>();

    record Resposta(int status, String corpo, CountDownLatch chegou, CountDownLatch liberar) {
        Resposta(int status, String corpo) { this(status, corpo, null, null); }
    }

    static String produto(long id, String nome, String preco) {
        return """
                {"id":%d,"nome":"%s","preco":%s,"ativo":true}
                """.formatted(id, nome, preco);
    }

    CatalogoHttpDeTeste() {
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException erro) {
            throw new IllegalStateException(erro);
        }
        servidor.setExecutor(executor);
        servidor.createContext("/", exchange -> {
            try (exchange) {
                String rota = exchange.getRequestURI().toString();
                chamadas.add(exchange.getRequestMethod() + " " + rota);
                Resposta resposta = new Resposta(404, "{}");
                if (exchange.getRequestMethod().equals("GET") && rota.matches("/api/v1/produtos/[0-9]+")) {
                    long id = Long.parseLong(rota.substring(rota.lastIndexOf('/') + 1));
                    resposta = produtos.getOrDefault(id, resposta);
                }
                if (resposta.chegou() != null) {
                    resposta.chegou().countDown();
                    try {
                        resposta.liberar().await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException erro) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (resposta.status() == 0) return; // Interrompe a conexão antes dos cabeçalhos.
                byte[] corpo = resposta.corpo().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(resposta.status(), corpo.length);
                exchange.getResponseBody().write(corpo);
            }
        });
        servidor.start();
    }

    String url() { return "http://127.0.0.1:" + servidor.getAddress().getPort(); }

    @Override
    public void close() {
        servidor.stop(0);
        executor.shutdownNow();
    }
}
