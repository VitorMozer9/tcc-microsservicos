# Executar contra bancos de teste. Os tres enderecos podem apontar para o mesmo monolito.
param(
    [string]$ProdutoUrl = 'http://localhost:8081',
    [string]$EstoqueUrl = 'http://localhost:8082',
    [string]$PedidoUrl = 'http://localhost:8083'
)
$ErrorActionPreference = 'Stop'

function Enviar($metodo, $url, $dados, $esperado) {
    $opcoes = @{ Method = $metodo; Uri = $url; UseBasicParsing = $true; TimeoutSec = 15 }
    if ($null -ne $dados) {
        $opcoes.Body = ConvertTo-Json $dados -Depth 5
        $opcoes.ContentType = 'application/json'
    }
    try {
        $resposta = Invoke-WebRequest @opcoes
        $status = [int]$resposta.StatusCode
    } catch {
        if (!$_.Exception.Response) { throw }
        $status = [int]$_.Exception.Response.StatusCode
    }
    if ($status -ne $esperado) { throw "$metodo $url retornou $status, esperado $esperado" }
    if ($status -lt 400 -and $resposta.Content) { return ($resposta.Content | ConvertFrom-Json) }
}

$produtos = "$ProdutoUrl/api/v1/produtos"
$estoques = "$EstoqueUrl/api/v1/estoques"
$pedidos = "$PedidoUrl/api/v1/pedidos"
$comSaldo = Enviar POST $produtos @{nome='Produto com saldo'; preco=1.25} 201
$semSaldo = Enviar POST $produtos @{nome='Produto sem estoque'; preco=2.50} 201
$null = Enviar POST $estoques @{produtoId=$comSaldo.id; quantidadeInicial=1} 201

function ConferirEstoque {
    $saldo = Enviar GET "$estoques/$($comSaldo.id)" $null 200
    if ($saldo.quantidadeDisponivel -ne 1) { throw 'Saldo alterado por Pedido' }
    Enviar GET "$estoques/$($semSaldo.id)" $null 404
}

ConferirEstoque
$pedido = Enviar POST $pedidos @{itens=@(@{produtoId=$semSaldo.id; quantidade=3})} 201
ConferirEstoque
$acima = Enviar POST $pedidos @{itens=@(@{produtoId=$comSaldo.id; quantidade=100})} 201
ConferirEstoque
$url = "$pedidos/$($pedido.id)"
$null = Enviar POST "$url/itens" @{produtoId=$comSaldo.id; quantidade=20} 200
ConferirEstoque
$null = Enviar PUT "$url/itens/$($comSaldo.id)" @{quantidade=30} 200
ConferirEstoque
$null = Enviar DELETE "$url/itens/$($comSaldo.id)" $null 200
ConferirEstoque
$null = Enviar POST "$pedidos/$($acima.id)/cancelamento" $null 200
ConferirEstoque
Write-Host 'Cadastro, inclusao, atualizacao, remocao e cancelamento preservaram os dois estoques.'
