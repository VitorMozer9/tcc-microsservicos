package com.github.vitormozer9.estoque.dto;

import jakarta.validation.constraints.*;

public record CadastroEstoqueRequest(@NotNull @Positive Long produtoId,
                                    @NotNull @PositiveOrZero Integer quantidadeInicial) {}
