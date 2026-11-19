package com.github.vitormozer9.verificacao;

import java.nio.file.Path;
import java.util.*;
import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ArquiteturaTest {
    private static final Path MODULO = Path.of("").toAbsolutePath();
    private static final String DOMINIO = MODULO.getFileName().toString().replace("-service", "");
    private static final String PROJETO = "com.github.vitormozer9.";
    private static final String LOCAL = PROJETO + DOMINIO + ".";
    private static final JavaClasses CLASSES = new ClassFileImporter().importPath(MODULO.resolve("target/classes"));

    @Test
    void tiposDeProducaoEReferenciasDoProjetoSaoLocais() {
        assertThat(CLASSES).isNotEmpty();
        for (var classe : CLASSES) {
            assertThat(classe.getPackageName() + ".").startsWith(LOCAL);
            for (var dep : classe.getDirectDependenciesFromSelf()) {
                var destino = dep.getTargetClass().getPackageName() + ".";
                if (destino.startsWith(PROJETO)) {
                    assertThat(destino).as(dep.getDescription()).startsWith(LOCAL);
                }
            }
        }
    }

    @Test
    void clientesHttpSomenteNaIntegracaoEConsultasSomenteNosFluxosPrevistos() {
        var origens = new HashSet<String>();
        for (var classe : CLASSES) {
            for (var dep : classe.getDirectDependenciesFromSelf()) {
                String alvo = dep.getTargetClass().getName();
                if (alvo.startsWith("org.springframework.web.client.") || alvo.startsWith("java.net.")
                        || alvo.startsWith("org.springframework.http.client.") || alvo.startsWith("okhttp3.")
                        || alvo.startsWith("org.apache.hc.") || alvo.startsWith("org.springframework.web.reactive.function.client.")) {
                    assertThat(DOMINIO).isNotEqualTo("produto");
                    assertThat(classe.getPackageName()).as(dep.getDescription()).isEqualTo(LOCAL + "integration");
                }
            }
            for (var call : classe.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getName().equals(LOCAL + "integration.ProdutoClient")
                        && !classe.getPackageName().equals(LOCAL + "integration")) {
                    assertThat(classe.getPackageName()).isEqualTo(LOCAL + "service");
                    String metodo = call.getOrigin().getName();
                    origens.add(metodo.startsWith("lambda$") ? metodo.split("\\$")[1] : metodo);
                }
            }
        }
        Set<String> esperado = switch (DOMINIO) {
            case "estoque" -> Set.of("cadastrar");
            case "pedido" -> Set.of("criar", "adicionar");
            case "produto" -> Set.of();
            default -> throw new AssertionError(DOMINIO);
        };
        assertThat(origens).isEqualTo(esperado);
    }

    @Test
    void endpointsDelegamUmaVezAoServicoLocal() {
        var controllers = CLASSES.stream().filter(c -> c.getPackageName().equals(LOCAL + "controller")).toList();
        assertThat(controllers).isNotEmpty();
        for (var controller : controllers) {
            for (var metodo : controller.getMethods()) {
                assertThat(metodo.getMethodCallsFromSelf()).as(metodo.getFullName()).hasSize(1)
                        .allMatch(call -> call.getTargetOwner().getPackageName().equals(LOCAL + "service"));
                assertThat(metodo.getConstructorCallsFromSelf()).isEmpty();
            }
        }
    }

    @Test
    void datasourceDeDesenvolvimentoPertenceAoModulo() throws Exception {
        var props = new Properties();
        try (var in = java.nio.file.Files.newInputStream(MODULO.resolve("target/classes/application.properties"))) {
            props.load(in);
        }
        assertThat(props.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:file:./data/" + DOMINIO);
    }
}
