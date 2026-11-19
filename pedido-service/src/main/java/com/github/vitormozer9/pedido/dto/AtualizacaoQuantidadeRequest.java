package com.github.vitormozer9.pedido.dto;

import jakarta.validation.constraints.*;

public record AtualizacaoQuantidadeRequest(@NotNull @Positive Integer quantidade) {}
