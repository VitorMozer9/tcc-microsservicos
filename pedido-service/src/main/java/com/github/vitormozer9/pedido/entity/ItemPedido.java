package com.github.vitormozer9.pedido.entity;

import java.math.BigDecimal;
import jakarta.persistence.*;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"pedido_id", "produto_id"}))
public class ItemPedido {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pedido_id", updatable = false)
    private Pedido pedido;

    @Column(name = "produto_id", nullable = false, updatable = false)
    private Long produtoId;

    @Column(nullable = false, updatable = false, length = 120)
    private String nomeProduto;

    private int quantidade;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal precoUnitario;

    protected ItemPedido() {}

    ItemPedido(Pedido pedido, ProdutoNoPedido produto, int quantidade) {
        this.pedido = pedido;
        this.produtoId = produto.id();
        this.nomeProduto = produto.nome();
        this.precoUnitario = produto.preco();
        alterarQuantidade(quantidade);
    }

    void alterarQuantidade(int quantidade) {
        if (quantidade <= 0) throw new IllegalArgumentException("Quantidade deve ser positiva.");
        this.quantidade = quantidade;
    }

    public BigDecimal getSubtotal() {
        return precoUnitario.multiply(BigDecimal.valueOf(quantidade));
    }

    public Long getProdutoId() { return produtoId; }
    public String getNomeProduto() { return nomeProduto; }
    public int getQuantidade() { return quantidade; }
    public BigDecimal getPrecoUnitario() { return precoUnitario; }
}
