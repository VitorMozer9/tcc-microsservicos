package com.github.vitormozer9.pedido.dto;

import java.math.BigDecimal;
import com.github.vitormozer9.pedido.entity.ItemPedido;

public record ItemPedidoResponse(Long produtoId, String nomeProduto, int quantidade,
                                 BigDecimal precoUnitario, BigDecimal subtotal) {
    static ItemPedidoResponse de(ItemPedido item) {
        return new ItemPedidoResponse(item.getProdutoId(), item.getNomeProduto(), item.getQuantidade(),
                item.getPrecoUnitario(), item.getSubtotal());
    }
}
