package com.github.vitormozer9.pedido.service;

import java.util.List;
import java.util.HashSet;
import org.springframework.transaction.support.TransactionTemplate;
import com.github.vitormozer9.pedido.integration.ProdutoClient;
import com.github.vitormozer9.pedido.exception.ConflitoDeNegocioException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.github.vitormozer9.pedido.dto.*;
import com.github.vitormozer9.pedido.entity.Pedido;
import com.github.vitormozer9.pedido.repository.PedidoRepository;
import com.github.vitormozer9.pedido.exception.RecursoNaoEncontradoException;

@Service
public class PedidoService {
    private final PedidoRepository pedidos;
    private final ProdutoClient produtos;
    private final TransactionTemplate transacao;

    public PedidoService(PedidoRepository pedidos, ProdutoClient produtos, TransactionTemplate transacao) {
        this.pedidos = pedidos;
        this.produtos = produtos;
        this.transacao = transacao;
    }

    public PedidoResponse criar(CriacaoPedidoRequest dados) {
        var ids = new HashSet<Long>();
        for (var item : dados.itens()) {
            if (!ids.add(item.produtoId())) {
                throw new ConflitoDeNegocioException("Produto duplicado no pedido.");
            }
        }
        var itens = dados.itens().iterator();
        var primeiro = itens.next();
        var pedido = new Pedido(produtos.buscarAtivo(primeiro.produtoId()), primeiro.quantidade());
        itens.forEachRemaining(item -> pedido.adicionar(produtos.buscarAtivo(item.produtoId()), item.quantidade()));
        return transacao.execute(tx -> PedidoResponse.de(pedidos.save(pedido)));
    }

    @Transactional(readOnly = true)
    public List<PedidoResponse> listar() {
        return pedidos.findAllByOrderById().stream().map(PedidoResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public PedidoResponse consultar(Long id) {
        return PedidoResponse.de(pedidos.findById(id).orElseThrow(() -> ausente(id)));
    }

    public PedidoResponse adicionar(Long id, ItemPedidoRequest item) {
        pedidos.findById(id).orElseThrow(() -> ausente(id)).exigirInclusaoPermitida(item.produtoId());
        var produto = produtos.buscarAtivo(item.produtoId());
        return transacao.execute(tx -> {
            var pedido = buscarParaAlterar(id);
            pedido.adicionar(produto, item.quantidade());
            return PedidoResponse.de(pedido);
        });
    }

    @Transactional
    public PedidoResponse atualizar(Long id, Long produtoId, AtualizacaoQuantidadeRequest dados) {
        var pedido = buscarParaAlterar(id);
        pedido.alterarQuantidade(produtoId, dados.quantidade());
        return PedidoResponse.de(pedido);
    }

    @Transactional
    public PedidoResponse remover(Long id, Long produtoId) {
        var pedido = buscarParaAlterar(id);
        pedido.remover(produtoId);
        return PedidoResponse.de(pedido);
    }

    @Transactional
    public PedidoResponse cancelar(Long id) {
        var pedido = buscarParaAlterar(id);
        pedido.cancelar();
        return PedidoResponse.de(pedido);
    }

    private Pedido buscarParaAlterar(Long id) {
        return pedidos.buscarParaAlterar(id).orElseThrow(() -> ausente(id));
    }

    private RecursoNaoEncontradoException ausente(Long id) {
        return new RecursoNaoEncontradoException("Pedido não encontrado: " + id);
    }
}
