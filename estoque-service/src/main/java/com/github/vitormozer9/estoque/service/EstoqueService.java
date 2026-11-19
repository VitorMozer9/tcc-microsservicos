package com.github.vitormozer9.estoque.service;

import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.github.vitormozer9.estoque.dto.*;
import com.github.vitormozer9.estoque.entity.Estoque;
import com.github.vitormozer9.estoque.exception.*;
import com.github.vitormozer9.estoque.integration.ProdutoClient;
import com.github.vitormozer9.estoque.repository.EstoqueRepository;

@Service
public class EstoqueService {
    private final EstoqueRepository estoques;
    private final ProdutoClient produtos;

    public EstoqueService(EstoqueRepository estoques, ProdutoClient produtos) {
        this.estoques = estoques;
        this.produtos = produtos;
    }

    public EstoqueResponse cadastrar(CadastroEstoqueRequest dados) {
        produtos.confirmarAtivo(dados.produtoId());
        var estoque = new Estoque(dados.produtoId(), dados.quantidadeInicial());
        try {
            return EstoqueResponse.de(estoques.saveAndFlush(estoque));
        } catch (DataIntegrityViolationException erro) {
            if (erro.getMostSpecificCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                throw new ConflitoDeNegocioException("Estoque já cadastrado para o produto.");
            }
            throw erro;
        }
    }

    @Transactional(readOnly = true)
    public EstoqueResponse consultar(Long produtoId) {
        return EstoqueResponse.de(buscar(produtoId));
    }

    @Transactional
    public EstoqueResponse movimentar(Long produtoId, MovimentacaoEstoqueRequest dados) {
        var estoque = buscar(produtoId);
        estoque.movimentar(dados.tipo(), dados.quantidade());
        return EstoqueResponse.de(estoque);
    }

    private Estoque buscar(Long produtoId) {
        return estoques.findById(produtoId).orElseThrow(
                () -> new RecursoNaoEncontradoException("Estoque não encontrado: " + produtoId));
    }
}
