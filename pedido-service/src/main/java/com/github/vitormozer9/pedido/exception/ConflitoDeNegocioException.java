package com.github.vitormozer9.pedido.exception;

public class ConflitoDeNegocioException extends RuntimeException {
    public ConflitoDeNegocioException(String mensagem) {
        super(mensagem);
    }
}
