package com.github.vitormozer9.estoque.dto;

import jakarta.validation.constraints.*;
import com.github.vitormozer9.estoque.entity.TipoMovimentacao;

public record MovimentacaoEstoqueRequest(@NotNull TipoMovimentacao tipo,
                                        @NotNull @Positive Integer quantidade) {}
