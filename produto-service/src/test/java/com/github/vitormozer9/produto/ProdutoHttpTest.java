package com.github.vitormozer9.produto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProdutoHttpTest extends ContratoProduto {
    @LocalServerPort
    private int porta;

    @Override
    String urlProdutos() {
        return "http://localhost:" + porta + "/api/v1/produtos";
    }

    @Test
    void exclusaoPreservaLinhaInativa(@Autowired JdbcTemplate jdbc) throws Exception {
        long id = cadastrar();
        assertThat(enviar("DELETE", "/" + id, null).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select ativo from produto where id = ?", Boolean.class, id)).isFalse();
    }

    @Test
    void aplicaPrecisaoDoModuloENormalizaNome() throws Exception {
        resposta("POST", "", dados("Produto", null, "10000000000.00"), 400);
        var criado = resposta("POST", "", dados("  Produto  ", null, "1.25"), 201);
        assertThat(criado.get("nome").stringValue()).isEqualTo("Produto");
    }

    @Test
    void rejeitaIdInvalido() throws Exception {
        for (String id : new String[]{"0", "-1", "abc"}) {
            var erro = resposta("GET", "/" + id, null, 400);
            assertThat(erro.get("erro").stringValue()).isNotBlank();
        }
    }
}
