package com.github.vitormozer9.pedido.entity;

import java.math.BigDecimal;

public record ProdutoNoPedido(Long id, String nome, BigDecimal preco) {}
