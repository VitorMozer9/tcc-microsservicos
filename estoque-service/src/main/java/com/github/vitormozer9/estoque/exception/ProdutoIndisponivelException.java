package com.github.vitormozer9.estoque.exception;

public class ProdutoIndisponivelException extends RuntimeException {
    public ProdutoIndisponivelException(Throwable causa) {
        super("Não foi possível consultar o serviço de Produto.", causa);
    }
}
