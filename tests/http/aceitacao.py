"""Contrato do baseline. Executar contra dados de teste; cria recursos pelas APIs."""
import argparse
import json
from contextlib import contextmanager
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def igual(obtido, esperado):
    if obtido != esperado:
        raise AssertionError(f"obtido {obtido!r}, esperado {esperado!r}")


class Suite:
    def __init__(self, args):
        self.p = args.produto.rstrip("/") + "/api/v1/produtos"
        self.e = args.estoque.rstrip("/") + "/api/v1/estoques"
        self.o = args.pedido.rstrip("/") + "/api/v1/pedidos"
        self.log = args.log
        self.arquitetura = args.arquitetura
        self.fase = args.fase

    def registrar(self, **dados):
        linha = json.dumps(dict(arquitetura=self.arquitetura, fase=self.fase, **dados), ensure_ascii=True)
        print(linha, flush=True)
        if self.log:
            with self.log.open("a", encoding="utf-8") as arquivo:
                arquivo.write(linha + "\n")

    @contextmanager
    def cenario(self, nome):
        try:
            yield
        except Exception as erro:
            self.registrar(cenario=nome, resultado="FALHA", erro=str(erro))
            raise
        self.registrar(cenario=nome, resultado="PASSOU")

    def http(self, metodo, url, status, dados=None):
        payload = None if dados is None else json.dumps(dados).encode()
        req = Request(url, data=payload, method=metodo, headers={"Content-Type": "application/json"})
        try:
            resposta = urlopen(req, timeout=12)
        except HTTPError as erro:
            resposta = erro
        with resposta:
            corpo = resposta.read()
            if self.log:
                registro = dict(arquitetura=self.arquitetura, fase=self.fase, metodo=metodo,
                                url=url, entrada=dados, esperado=status, status=resposta.status,
                                resposta=corpo.decode("utf-8", errors="replace"))
                with self.log.with_suffix(".http.jsonl").open("a", encoding="utf-8") as arquivo:
                    arquivo.write(json.dumps(registro, ensure_ascii=True) + "\n")
            if resposta.status not in (status if isinstance(status, tuple) else (status,)):
                raise AssertionError(f"{metodo} {url}: HTTP {resposta.status}, esperado {status}; {corpo[:500]!r}")
            if resposta.status == 204:
                igual(corpo, b"")
                return
            igual(resposta.headers.get_content_type(), "application/json")
            obj = json.loads(corpo, parse_float=Decimal)
            if resposta.status in (404, 409, 503) and not isinstance(status, tuple):
                if not isinstance(obj.get("erro"), str) or not obj["erro"]:
                    raise AssertionError(f"Erro de negocio sem mensagem: {obj}")
            return (resposta.status, obj) if isinstance(status, tuple) else obj

    def produto(self, nome="Produto A", preco=2.50):
        obj = self.http("POST", self.p, 201, dict(nome=nome, descricao="Teste", preco=preco))
        igual(set(obj), {"id", "nome", "descricao", "preco", "ativo"})
        igual(type(obj["id"]), int)
        igual(obj["ativo"] is True, True)
        igual((obj["nome"], obj["descricao"], obj["preco"]), (nome, "Teste", Decimal(str(preco))))
        return obj["id"]

    def saldo(self, id, esperado):
        igual(self.http("GET", f"{self.e}/{id}", 200),
              dict(produtoId=id, quantidadeDisponivel=esperado))

    def criar(self, id, qtd, status=201):
        return self.http("POST", self.o, status, {"itens": [item(id, qtd)]})


def item(id, qtd):
    return dict(produtoId=id, quantidade=qtd)


def comum(s):
    with s.cenario("A1 produto CRUD e exclusao logica"):
        id = s.produto()
        url = f"{s.p}/{id}"
        igual(s.http("GET", url, 200)["id"], id)
        igual(id in [p["id"] for p in s.http("GET", s.p, 200)], True)
        atualizado = s.http("PUT", url, 200, dict(nome="Novo", descricao=None, preco=3.25))
        igual(atualizado, dict(id=id, nome="Novo", descricao=None, preco=Decimal("3.25"), ativo=True))
        igual(s.http("GET", url, 200), atualizado)
        s.http("DELETE", url, 204)
        s.http("GET", url, 404)
        lista = s.http("GET", s.p, 200)
        igual(all(p["ativo"] is True and p["id"] != id for p in lista), True)
        s.http("POST", s.p, 400, dict(nome="", preco=-1))

    with s.cenario("A2 estoque cadastro, entrada, saida e conflitos"):
        a, b = s.produto(), s.produto("Produto B", 0.10)
        cadastro = dict(produtoId=a, quantidadeInicial=3)
        igual(s.http("POST", s.e, 201, cadastro), dict(produtoId=a, quantidadeDisponivel=3))
        s.http("POST", s.e, 409, cadastro)
        mov = f"{s.e}/{a}/movimentacoes"
        for tipo, qtd, fim in [("ENTRADA", 2, 5), ("SAIDA", 4, 1)]:
            igual(s.http("POST", mov, 200, dict(tipo=tipo, quantidade=qtd)),
                  dict(produtoId=a, quantidadeDisponivel=fim))
        s.http("POST", mov, 409, dict(tipo="SAIDA", quantidade=2))
        s.saldo(a, 1)

    def estoques():
        s.saldo(a, 1)
        s.http("GET", f"{s.e}/{b}", 404)

    with s.cenario("A3 criar sem estoque e acima do saldo"):
        estoques()
        pedido = s.criar(b, 3)
        igual(set(pedido), {"id", "status", "criadoEm", "itens", "total"})
        igual(pedido["status"], "ABERTO")
        instante = datetime.fromisoformat(pedido["criadoEm"].replace("Z", "+00:00"))
        igual(instante.utcoffset(), timezone.utc.utcoffset(None))
        igual(pedido["itens"], [dict(produtoId=b, nomeProduto="Produto B", quantidade=3,
                                     precoUnitario=Decimal("0.10"), subtotal=Decimal("0.30"))])
        igual(pedido["total"], Decimal("0.30"))
        estoques()
        igual(s.criar(a, 100)["total"], Decimal("250"))
        estoques()
        url = f"{s.o}/{pedido['id']}"

    with s.cenario("A4 incluir, preservar historico, alterar, remover"):
        adicao = s.http("POST", url + "/itens", 200, item(a, 2))
        igual(adicao["total"], Decimal("5.30"))
        estoques()
        s.http("PUT", f"{s.p}/{a}", 200, dict(nome="Reajustado", preco=10))
        edicao = s.http("PUT", f"{url}/itens/{a}", 200, {"quantidade":4})
        linha = next(i for i in edicao["itens"] if i["produtoId"] == a)
        igual(linha, dict(produtoId=a, nomeProduto="Produto A", quantidade=4,
                          precoUnitario=Decimal("2.50"), subtotal=Decimal("10")))
        igual(edicao["total"], Decimal("10.30"))
        estoques()
        removido = s.http("DELETE", f"{url}/itens/{b}", 200)
        igual(removido["itens"], [linha])
        igual(removido["total"], Decimal("10"))
        estoques()
        s.http("DELETE", f"{url}/itens/{a}", 409)

    with s.cenario("A5 cancelar, consultar, listar e bloquear alteracoes"):
        cancelado = s.http("POST", url + "/cancelamento", 200)
        igual(cancelado["status"], "CANCELADO")
        igual(cancelado["itens"], [linha])
        igual(cancelado["total"], Decimal("10"))
        estoques()
        igual(s.http("GET", url, 200), cancelado)
        igual(cancelado in s.http("GET", s.o, 200), True)
        s.http("PUT", f"{url}/itens/{a}", 409, {"quantidade":2})
        s.http("POST", url + "/cancelamento", 409)
        estoques()


def diferencas(s):
    # Sondas descritivas: registrar a observacao sem transformar divergencias em equivalencia.
    with s.cenario("B sondas de comparabilidade"):
        codigo, _ = s.http("POST", s.p, (201,400), dict(nome="Limite", preco=10000000000))
        _, nome = s.http("POST", s.p, (201,), dict(nome="  Espacos  ", preco=1))
        codigo_id, _ = s.http("GET", s.p + "/0", (400,404))
        ids = [x["id"] for x in s.http("GET", s.p, 200)]
        s.registrar(preco_11_digitos=codigo, nome_devolvido=nome["nome"], id_zero=codigo_id,
                    lista_observada_crescente=ids == sorted(ids), ids=ids)


def distribuido(s, fase, estado):
    if fase == "preparar":
        with s.cenario("D1 dados para falhas e reinicio"):
            a, b = s.produto("Persistido"), s.produto("Sem saldo")
            s.http("POST", s.e, 201, dict(produtoId=a, quantidadeInicial=4))
            pedido = s.criar(a, 2)
            estado.write_text(json.dumps(dict(a=a,b=b,id=pedido["id"])), encoding="utf-8")
        return
    dados = json.loads(estado.read_text(encoding="utf-8"))
    a, b = dados["a"], dados["b"]
    url = f"{s.o}/{dados['id']}"
    if fase == "sem-produto":
        with s.cenario("D2 Produto desligado: tres inclusoes retornam 503"):
            s.http("POST", s.e, 503, dict(produtoId=b, quantidadeInicial=1))
            s.criar(b, 1, 503)
            s.http("POST", url + "/itens", 503, item(b, 1))
            s.http("GET", f"{s.e}/{b}", 404)
        with s.cenario("D3 Produto desligado: operacoes locais"):
            s.saldo(a, 4)
            s.http("POST", f"{s.e}/{a}/movimentacoes", 200, dict(tipo="SAIDA", quantidade=1))
            s.saldo(a, 3)
            igual(s.http("GET", url, 200)["total"], 5)
            igual(s.http("PUT", f"{url}/itens/{a}", 200, dict(quantidade=3))["total"], Decimal("7.50"))
            igual(s.http("POST", url+"/cancelamento", 200)["status"], "CANCELADO")
    elif fase == "sem-estoque":
        with s.cenario("D4 Estoque desligado: criar, consultar e cancelar"):
            pedido = s.criar(b, 500)
            novo = f"{s.o}/{pedido['id']}"
            igual(s.http("GET", novo, 200)["total"], 1250)
            igual(s.http("POST", novo+"/cancelamento", 200)["status"], "CANCELADO")
    elif fase == "reiniciado":
        with s.cenario("D5 recuperar catalogo, saldo, pedido e itens"):
            igual(s.http("GET", f"{s.p}/{a}", 200)["nome"], "Persistido")
            s.saldo(a, 3)
            s.http("GET", f"{s.e}/{b}", 404)
            pedido = s.http("GET", url, 200)
            igual(pedido["status"], "CANCELADO")
            igual(pedido["total"], Decimal("7.50"))
            igual(pedido["itens"], [dict(produtoId=a, nomeProduto="Persistido", quantidade=3,
                                         precoUnitario=Decimal("2.50"), subtotal=Decimal("7.50"))])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for nome, porta in [("produto",8081), ("estoque",8082), ("pedido",8083)]:
        parser.add_argument("--"+nome, default=f"http://localhost:{porta}")
    parser.add_argument("--fase", choices=["comum","diferencas","preparar","sem-produto","sem-estoque","reiniciado"], default="comum")
    parser.add_argument("--estado", type=Path)
    parser.add_argument("--log", type=Path)
    parser.add_argument("--arquitetura", default="externa")
    args = parser.parse_args()
    suite = Suite(args)
    if args.fase == "comum":
        comum(suite)
    elif args.fase == "diferencas":
        diferencas(suite)
    elif args.estado:
        distribuido(suite, args.fase, args.estado)
    else:
        parser.error("As fases distribuidas exigem --estado")

