package com.github.vitormozer9.estoque.integration;

import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import com.github.vitormozer9.estoque.exception.*;

@Component
public class ProdutoClient {
    private final RestClient http;

    public ProdutoClient(RestClient http) {
        this.http = http;
    }

    public void confirmarAtivo(Long id) {
        ProdutoConsultado produto;
        try {
            produto = http.get().uri("/api/v1/produtos/{id}", id)
                    .retrieve().body(ProdutoConsultado.class);
        } catch (HttpClientErrorException.NotFound erro) {
            throw ausente(id);
        } catch (RestClientException erro) {
            throw new ProdutoIndisponivelException(erro);
        }
        if (produto == null || !id.equals(produto.id()) || produto.ativo() == null) {
            throw new ProdutoIndisponivelException(new IllegalStateException("Resposta de Produto inválida."));
        }
        if (!produto.ativo()) {
            throw ausente(id);
        }
    }

    private RecursoNaoEncontradoException ausente(Long id) {
        return new RecursoNaoEncontradoException("Produto ativo não encontrado: " + id);
    }

    private record ProdutoConsultado(Long id, Boolean ativo) {}
}
