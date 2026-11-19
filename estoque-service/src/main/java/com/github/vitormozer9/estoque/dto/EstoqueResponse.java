package com.github.vitormozer9.estoque.dto;

import com.github.vitormozer9.estoque.entity.Estoque;

public record EstoqueResponse(Long produtoId, int quantidadeDisponivel) {
    public static EstoqueResponse de(Estoque estoque) {
        return new EstoqueResponse(estoque.getProdutoId(), estoque.getQuantidadeDisponivel());
    }
}
