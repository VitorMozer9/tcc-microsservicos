package com.github.vitormozer9.estoque;

import org.junit.jupiter.api.Test;
import com.github.vitormozer9.estoque.entity.*;
import com.github.vitormozer9.estoque.exception.ConflitoDeNegocioException;
import static org.assertj.core.api.Assertions.*;

class EstoqueTest {
    @Test
    void entradaAcimaDoLimitePreservaSaldo() {
        var estoque = new Estoque(1L, Integer.MAX_VALUE);
        assertThatThrownBy(() -> estoque.movimentar(TipoMovimentacao.ENTRADA, 1))
                .isInstanceOf(ConflitoDeNegocioException.class);
        assertThat(estoque.getQuantidadeDisponivel()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void saidaPodeConsumirTodoOSaldo() {
        var estoque = new Estoque(1L, 10);
        estoque.movimentar(TipoMovimentacao.SAIDA, 10);
        assertThat(estoque.getQuantidadeDisponivel()).isZero();
    }
}
