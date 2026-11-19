# Etapa 5 — integração, aceitação e arquitetura

Em 19/11/2026, os cinco cenários comuns passaram nos três sistemas. Cada arquitetura recebeu as mesmas 45 requisições nesses cenários, cobrindo as 15 combinações de método e rota do baseline. A conclusão de equivalência funcional se restringe aos cenários comuns executados. As sondas específicas detectaram diferenças de validação e representação.

## Ambiente e identificação

Windows 11 amd64, build 26200, PowerShell 5.1.26100.9818, Python 3.14.0 (biblioteca padrão), Git 2.47.0.windows.2, Oracle JDK e javac 23.0.2, compilação Java 21, Spring Boot 4.1.2 e Maven Wrapper 3.9.15. Os testes de módulo usaram seus perfis de teste com H2 em memória. Os testes de processos usaram H2 em arquivo, portas efêmeras na interface de loopback e diretórios novos por execução.

| Aplicação | Fonte executada |
|---|---|
| Microsserviços | Três JARs gerados pelo `clean verify` desta etapa |
| Camadas | Tag `baseline-crud-v1`, commit `c357422db26f822912d201eb734775f049ef5126` |
| Modular | Tag `baseline-crud-v1`, commit `0458dc5490a11113ca09b9617ed8dab1b83c47a6` |

O script resolveu cada tag para um commit, exportou seus arquivos por `git archive` e executou Maven apenas na cópia. Para padronizar a persistência do ensaio, os processos receberam configuração de H2 em arquivo e `ddl-auto=update`, inclusive o modular, cujo padrão da tag usa memória. Os testes internos dos monólitos foram desabilitados no empacotamento. A comparação entre arquiteturas utilizou a suíte HTTP externa.

## Maven e regras arquiteturais

`.\mvnw.cmd clean verify` retornou código 0 e `BUILD SUCCESS` em 57,792 segundos. O Maven Enforcer passou na fase de validação. A regra proíbe dependências Maven diretas e transitivas de artefatos `com.github.vitormozer9:*-service` no grafo de cada módulo.

| Módulo | Testes da aplicação | Testes de arquitetura | Total | Falhas / erros / ignorados |
|---|---:|---:|---:|---|
| Produto | 19 | 4 | 23 | 0 / 0 / 0 |
| Estoque | 16 | 4 | 20 | 0 / 0 / 0 |
| Pedido | 25 | 4 | 29 | 0 / 0 / 0 |
| **Total** | **60** | **12** | **72** | **0 / 0 / 0** |

As contagens e tempos por classe foram extraídos dos XMLs do Surefire. [Resumo Surefire](evidencias/etapa5/surefire.csv)

| Restrição | Verificação executada |
|---|---|
| Isolamento Java, entidades e repositórios | ArchUnit inspeciona as classes de produção compiladas e suas dependências no namespace do projeto. Cada módulo referencia apenas tipos de seu domínio. O Enforcer complementa a análise no grafo Maven. |
| Clientes HTTP e direção das chamadas | Dependências das APIs de cliente usadas no projeto só são admitidas em `integration` dos consumidores. Produto não pode usá-las. A análise dos chamadores de `ProdutoClient` identificou somente cadastro em Estoque e criação/adição em Pedido. Os testes dos módulos conferiram método, rota e contagem de chamadas ao catálogo controlado. |
| Responsabilidade dos controllers | Nos controllers atuais, cada método delega exatamente uma chamada ao serviço local e não instancia objetos. Essa propriedade estrutural é verificada no bytecode. A aplicação das regras também foi exercitada por HTTP. |
| Bancos exclusivos | Um teste por módulo confere a URL H2 configurada em desenvolvimento. Após encerrar as três JVMs, a inspeção JDBC abriu cada arquivo existente em modo de leitura e comparou o conjunto completo de tabelas de `PUBLIC`. Os resultados foram Produto → `PRODUTO`, Estoque → `ESTOQUE`, Pedido → `PEDIDO` e `ITEM_PEDIDO`. |
| Independência operacional | Leituras e movimentações de Estoque continuaram com Produto desligado. Pedido criou, consultou e cancelou com Estoque desligado. Os saldos também foram verificados com os três processos ativos. |

As verificações estruturais cobrem o bytecode e as APIs de transporte presentes no projeto. A regra dos controllers verifica delegação e construção de objetos, sem pretensão de classificar semanticamente toda regra de negócio possível. [Teste arquitetural](../tests/architecture/ArquiteturaTest.java)

## Aceitação real

A suíte usou IDs retornados pelos servidores e conferiu status, JSON, valores monetários com aritmética decimal, timestamp UTC, conteúdo das listas e efeitos observados em chamadas posteriores. Nos erros de negócio previstos nos cenários comuns exigiu a chave `erro`. Cada grupo A1–A5 passou em microsserviços, camadas e modular.

| Grupo | Operações e evidência |
|---|---|
| A1 · Produto | Criar, consultar, listar, atualizar e excluir. A consulta do inativo retornou 404 e a listagem o omitiu. |
| A2 · Estoque | Cadastro com saldo 3, entrada de 2 e saída de 4. Duplicidade e saída acima do saldo retornaram 409. Saldo final 1. |
| A3 · Criação de Pedido | Um produto sem registro de estoque permitiu pedido de 3 unidades. Outro produto com saldo 1 permitiu pedido de 100 unidades. Após cada criação, o primeiro estoque continuou ausente e o segundo permaneceu com saldo 1. |
| A4 · Itens e histórico | Adição, alteração e remoção preservaram os dois estados de estoque após cada operação. O catálogo passou de “Produto A”/2,50 para “Reajustado”/10,00. O item manteve o nome original e o preço 2,50. Quatro unidades deram subtotal 10,00 e total 10,30 antes da remoção do outro item. Remover o último item retornou 409. |
| A5 · Cancelamento | O pedido cancelado preservou os itens e o total na consulta e na listagem. Alterar a quantidade e cancelar novamente retornaram 409. O saldo permaneceu 1 e o produto sem estoque continuou retornando 404. |

### Sondas específicas B

As sondas foram executadas após os cenários comuns. Os códigos e valores abaixo foram registrados como observações, sem alterar os resultados esperados de A1–A5.

| Entrada ou observação | Microsserviços | Camadas | Modular |
|---|---|---|---|
| Cadastro com preço 10000000000 (11 dígitos inteiros) | 400 | 201 | 400 |
| Cadastro com dois espaços antes e depois de `Espacos` | Devolveu `Espacos` | Preservou os quatro espaços | Devolveu `Espacos` |
| Consulta `GET /api/v1/produtos/0` | 400 | 400 | 404 |
| Ordem de IDs na listagem de ativos | Crescente | Crescente | Crescente |

O código de Produto explicita dez dígitos inteiros nos microsserviços e no modular e dezessete em camadas. A sonda acima verificou a fronteira entre dez e onze. A ordenação por ID é explícita nos repositórios de Produto dos microsserviços e de camadas. O modular usa `findByAtivoTrue()` sem ordenação explícita. A sequência observada no modular vale para esta execução.

### Indisponibilidade e reinicialização

- **D1/D2.** Com os três sistemas ativos, foram criados produtos, saldo e pedido para o ensaio. Após o encerramento de Produto, o cadastro de estoque, a criação de pedido e a inclusão de item retornaram 503. O cadastro malsucedido continuou ausente, com 404 na consulta local.
- **D3.** Ainda com Produto desligado, Estoque consultou saldo 4 e processou uma saída de 1. Pedido consultou o pedido, alterou a quantidade de 2 para 3 e o cancelou. O total passou de 5,00 para 7,50.
- **D4.** Após reiniciar Produto e encerrar Estoque, Pedido criou um pedido de 500 unidades, consultou o total 1250,00 e cancelou.
- **D5.** Após fechar todas as JVMs, inspecionar os três arquivos H2 e reiniciar os serviços, as APIs recuperaram o produto “Persistido”, o saldo 3 e o pedido cancelado com quantidade 3, nome original, preço 2,50 e total 7,50. O outro produto continuou sem registro de estoque.

O executor usa o `JarLauncher` original de cada JAR e um sinal exclusivo da instância para acionar os shutdown hooks da JVM. O script espera a saída do processo, valida seu código e só então prossegue. Os bancos e logs ficam na pasta identificada no início da execução, dentro de `target/etapa5`. Uma nova execução cria outro diretório.

### Registros preservados

As execuções finais retornaram código 0 nos dois modos. Foram **168 requisições da suíte**: 45 comuns e quatro sondas em cada arquitetura, mais 21 chamadas nas fases distribuídas. As sondagens de prontidão, as verificações JDBC e o tráfego interno entre serviços ficam fora dessa contagem. Os arquivos HTTP registram método, URL, entrada, status esperado e observado e corpo recebido.

| Execução | Cenários | Requisições |
|---|---|---|
| Microsserviços · `280f1478848946df9aaa558ca5e5ffee` | [Resultados e inventários dos bancos](evidencias/etapa5/microsservicos-cenarios.jsonl) | [70 chamadas](evidencias/etapa5/microsservicos-http.jsonl) |
| Referências · `6f74ae5ca7ae4e08bcc532222e90f27f` | [Resultados e commits](evidencias/etapa5/referencias-cenarios.jsonl) | [98 chamadas](evidencias/etapa5/referencias-http.jsonl) |

Na primeira tentativa em cada modo, o PowerShell perdeu o acesso ao código de saída após a parada de uma JVM. Os scripts terminaram com falha. O executor passou a reter o handle antes da parada e ambas as execuções foram repetidas integralmente em bancos novos. O diagnóstico de versão do Wrapper também exigiu acesso ao cache Maven do usuário no ambiente de execução. Os resultados acima correspondem às execuções finais.

## Reprodução no Windows

Na raiz do repositório, usar JDK 23 com `java` e `javac` no PATH, Python 3.11 ou superior, PowerShell e Git. O Wrapper precisa de acesso ao cache Maven e aos artefatos que ainda não estiverem presentes.

```powershell
.\mvnw.cmd -version
.\mvnw.cmd clean verify
.\scripts\smoke-baseline.ps1
.\scripts\smoke-baseline.ps1 -Modo Referencias
```

O modo de referências procura os dois diretórios irmãos `tcc-monolito-camadas/pedidosestoque` e `tcc-monolito-modular/pedidosestoque`. Para outro diretório pai, passe `-DiretorioDosRepos`. O build dos microsserviços deve preceder os scripts. Um novo `clean` remove os artefatos temporários de `target`; as evidências preservadas neste relatório ficam em `docs/evidencias/etapa5`.

Para executar apenas os cenários comuns ou as sondas contra uma aplicação já iniciada, use bases de **teste**. A suíte cria e altera recursos identificados pelas respostas do próprio ensaio.

```powershell
python tests/http/aceitacao.py
python tests/http/aceitacao.py --fase diferencas
python tests/http/aceitacao.py --produto http://localhost:8080 --estoque http://localhost:8080 --pedido http://localhost:8080 --arquitetura monolito
```

Os ensaios demonstram comportamento para as entradas, versões e ambiente descritos. Não medem desempenho, disponibilidade sob carga, recuperação após queda abrupta nem equivalência em todo o espaço de entradas.

