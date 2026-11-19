package com.github.vitormozer9.estoque.exception;

public class ConflitoDeNegocioException extends RuntimeException {
    public ConflitoDeNegocioException(String mensagem) {
        super(mensagem);
    }
}
