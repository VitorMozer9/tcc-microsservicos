package com.github.vitormozer9.estoque.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.github.vitormozer9.estoque.entity.Estoque;

public interface EstoqueRepository extends JpaRepository<Estoque, Long> {}
