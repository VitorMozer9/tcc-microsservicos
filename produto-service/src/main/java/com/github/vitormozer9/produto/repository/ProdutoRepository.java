package com.github.vitormozer9.produto.repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import com.github.vitormozer9.produto.entity.Produto;

public interface ProdutoRepository extends JpaRepository<Produto, Long> {
    Optional<Produto> findByIdAndAtivoTrue(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Produto p where p.id = :id and p.ativo = true")
    Optional<Produto> buscarAtivoParaAlteracao(Long id);

    List<Produto> findByAtivoTrueOrderById();
}
