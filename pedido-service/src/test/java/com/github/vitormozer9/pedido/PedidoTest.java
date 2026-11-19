package com.github.vitormozer9.pedido;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import com.github.vitormozer9.pedido.entity.*;
import com.github.vitormozer9.pedido.exception.ConflitoDeNegocioException;
import static org.assertj.core.api.Assertions.*;

class PedidoTest {
    @Test
    void totaisSaoExatosMesmoComQuantidadesGrandes() {
        var pedido = new Pedido(new ProdutoNoPedido(1L, "A", new BigDecimal("9999999999.99")), 2_000_000_000);
        pedido.adicionar(new ProdutoNoPedido(2L, "B", new BigDecimal("0.10")), 3);
        assertThat(pedido.getTotal()).isEqualByComparingTo("19999999999980000000.30");
    }

    @Test
    void tentativaDeRemoverUltimoItemPreservaPedido() {
        var pedido = new Pedido(new ProdutoNoPedido(1L, "A", BigDecimal.ONE), 2);
        assertThatThrownBy(() -> pedido.remover(1L)).isInstanceOf(ConflitoDeNegocioException.class);
        assertThat(pedido.getTotal()).isEqualByComparingTo("2");
    }
}
