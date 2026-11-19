package com.github.vitormozer9.estoque.entity;

import jakarta.persistence.*;
import com.github.vitormozer9.estoque.exception.ConflitoDeNegocioException;

@Entity
public class Estoque {
    @Id
    private Long produtoId;

    @Column(nullable = false)
    private int quantidadeDisponivel;

    @Version
    private Long versao;

    protected Estoque() {}

    public Estoque(Long produtoId, int quantidadeInicial) {
        if (produtoId == null || produtoId <= 0 || quantidadeInicial < 0) {
            throw new IllegalArgumentException("Produto ou quantidade inicial inválidos.");
        }
        this.produtoId = produtoId;
        this.quantidadeDisponivel = quantidadeInicial;
    }

    public void movimentar(TipoMovimentacao tipo, int quantidade) {
        if (tipo == null || quantidade <= 0) {
            throw new IllegalArgumentException("Tipo ou quantidade de movimentação inválidos.");
        }
        if (tipo == TipoMovimentacao.SAIDA) {
            if (quantidade > quantidadeDisponivel) {
                throw new ConflitoDeNegocioException("Saldo insuficiente.");
            }
            quantidadeDisponivel -= quantidade;
        } else {
            if (quantidade > Integer.MAX_VALUE - quantidadeDisponivel) {
                throw new ConflitoDeNegocioException("Saldo excede o limite permitido.");
            }
            quantidadeDisponivel += quantidade;
        }
    }

    public Long getProdutoId() { return produtoId; }
    public int getQuantidadeDisponivel() { return quantidadeDisponivel; }
}
