package com.github.vitormozer9.pedido.exception;

import java.util.Map;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(RecursoNaoEncontradoException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> ausente(RecursoNaoEncontradoException erro) {
        return Map.of("erro", erro.getMessage());
    }

    @ExceptionHandler(ConflitoDeNegocioException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflito(ConflitoDeNegocioException erro) {
        return Map.of("erro", erro.getMessage());
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> concorrencia() {
        return Map.of("erro", "Conflito de acesso ao pedido.");
    }

    @ExceptionHandler(ProdutoIndisponivelException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> indisponivel(ProdutoIndisponivelException erro) {
        return Map.of("erro", erro.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalido() {
        return Map.of("erro", "Dados de pedido inválidos.");
    }
}
