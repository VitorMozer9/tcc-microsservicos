[CmdletBinding()]
param(
    [ValidateSet('Microsservicos','Referencias')] [string]$Modo = 'Microsservicos',
    [string]$DiretorioDosRepos = (Join-Path $PSScriptRoot '../..')
)
$ErrorActionPreference = 'Stop'
$raiz = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$run = (New-Item -ItemType Directory -Path (Join-Path $raiz ("target/etapa5/" + [guid]::NewGuid().ToString('N'))) -Force).FullName
$classes = (New-Item -ItemType Directory -Path (Join-Path $run 'classes')).FullName
$log = Join-Path $run 'resultados.jsonl'
$estado = Join-Path $run 'estado.json'
$processos = @{}
$portas = @{}
$urls = @{}
$arquitetura = 'microsservicos'
Write-Host "Evidencias: $run"

function Registrar($nome, $resultado, $detalhe) {
    $registro = [ordered]@{arquitetura=$arquitetura; cenario=$nome; resultado=$resultado; detalhe=$detalhe}
    $linha = ConvertTo-Json $registro -Compress
    [IO.File]::AppendAllText($log, $linha + [Environment]::NewLine)
    Write-Host $linha
}

function Iniciar($nome, $jar) {
    $dir = Join-Path $run $nome
    [void](New-Item -ItemType Directory -Force -Path $dir)
    $vez = [guid]::NewGuid().ToString('N')
    $stdout = Join-Path $dir "$vez.log"
    $sinal = "$vez.stop"
    $porta = if ($portas.ContainsKey($nome)) { $portas[$nome] } else { 0 }
    $opcoes = @('-cp', ('"' + $classes + ';' + $jar + '"'), 'AplicacaoDeTeste', $sinal,
        '--server.address=127.0.0.1', "--server.port=$porta",
        "--spring.datasource.url=jdbc:h2:file:./data/$nome", '--spring.datasource.username=sa',
        '--spring.datasource.password=', '--spring.jpa.hibernate.ddl-auto=update')
    if ($nome -in 'estoque','pedido') { $opcoes += "--produto.base-url=$($urls['produto'])" }
    $proc = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList $opcoes -PassThru -WindowStyle Hidden -WorkingDirectory $dir -RedirectStandardOutput $stdout -RedirectStandardError (Join-Path $dir "$vez.err")
    # No Windows PowerShell, retenha o handle antes do termino para ler ExitCode.
    $proc = [Diagnostics.Process]::GetProcessById($proc.Id)
    $null = $proc.Handle
    $processos[$nome] = @{proc=$proc; sinal=(Join-Path $dir $sinal)}
    $limite = [datetime]::UtcNow.AddSeconds(90)
    while ([datetime]::UtcNow -lt $limite) {
        if ($proc.HasExited) { throw "$nome encerrou durante a inicializacao. Consulte $stdout" }
        $linha = Select-String -LiteralPath $stdout -Pattern 'Tomcat started on port (\d+)' | Select-Object -Last 1
        if ($linha) {
            $portas[$nome] = [int]$linha.Matches[0].Groups[1].Value
            $url = "http://127.0.0.1:$($portas[$nome])"
            $rota = switch ($nome) { 'estoque' {'estoques/1'} 'pedido' {'pedidos'} default {'produtos'} }
            try {
                $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "$url/api/v1/$rota"
                if ($r.StatusCode -eq 200) { $urls[$nome]=$url; return }
            } catch {
                if ($nome -eq 'estoque' -and $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) {
                    $urls[$nome]=$url; return
                }
            }
        }
        Start-Sleep -Milliseconds 300
    }
    throw "Servico indisponivel: $nome ($stdout)"
}

function Encerrar($nome) {
    $instancia = $processos[$nome]
    $proc = $instancia.proc
    if ($proc.HasExited) { throw "$nome encerrou antes do sinal de parada." }
    [IO.File]::WriteAllText($instancia.sinal, 'stop')
    if (!$proc.WaitForExit(30000)) { throw "$nome nao concluiu o encerramento controlado." }
    if ($proc.ExitCode -ne 0) { throw "$nome encerrou com codigo $($proc.ExitCode)." }
    $processos.Remove($nome)
}

function Suite($fase) {
    & python (Join-Path $raiz 'tests/http/aceitacao.py') --produto $urls.produto --estoque $urls.estoque --pedido $urls.pedido --log $log --fase $fase --estado $estado --arquitetura $arquitetura
    if ($LASTEXITCODE -ne 0) { throw "Falhou a fase $fase ($arquitetura)" }
}

function ConferirBancos($jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    $driver = Join-Path $run 'driver-h2.jar'
    try {
        $entry = @($zip.Entries | Where-Object FullName -match '^BOOT-INF/lib/h2-[0-9].*\.jar$')
        if ($entry.Count -ne 1) { throw 'Driver H2 ausente ou ambiguo' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry[0], $driver)
    } finally { $zip.Dispose() }
    $tabelas = @{produto=@('PRODUTO'); estoque=@('ESTOQUE'); pedido=@('PEDIDO','ITEM_PEDIDO')}
    foreach ($nome in $tabelas.Keys) {
        $banco = Join-Path $run "$nome/data/$nome"
        $esperadas = $tabelas[$nome]
        & java -cp "$classes;$driver" TabelasH2 $banco @esperadas
        if ($LASTEXITCODE -ne 0) { throw "Tabelas inesperadas em $nome" }
        Registrar "Tabelas de $nome" 'PASSOU' ($esperadas -join ', ')
    }
}

try {
    $fontes = (Get-ChildItem -Path (Join-Path $raiz 'tests/processos/*.java')).FullName
    & javac --release 21 -d $classes @fontes
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar auxiliares do smoke test' }

    if ($Modo -eq 'Microsservicos') {
        $jars = @{}
        foreach ($nome in 'produto','estoque','pedido') {
            $jars[$nome] = (Resolve-Path (Join-Path $raiz "$nome-service/target/$nome-service-0.0.1-SNAPSHOT.jar")).Path
            Iniciar $nome $jars[$nome]
        }
        Suite comum
        Suite diferencas
        Suite preparar
        Encerrar produto
        Suite sem-produto
        Iniciar produto $jars.produto
        Encerrar estoque
        Suite sem-estoque
        Encerrar pedido
        Encerrar produto
        ConferirBancos $jars.produto
        foreach ($nome in 'produto','estoque','pedido') { Iniciar $nome $jars[$nome] }
        Suite reiniciado
        foreach ($nome in 'pedido','estoque','produto') { Encerrar $nome }
    } else {
        foreach ($modelo in 'camadas','modular') {
            $arquitetura = $modelo
            $repo = (Resolve-Path (Join-Path $DiretorioDosRepos "tcc-monolito-$modelo/pedidosestoque")).Path.Replace('\','/')
            $ref = & git -c "safe.directory=$repo" -C $repo rev-parse 'refs/tags/baseline-crud-v1^{commit}'
            if ($LASTEXITCODE -ne 0) { throw "Tag indisponivel: $repo" }
            $fonte = Join-Path $run "fontes-$modelo"
            $pacote = Join-Path $run "$modelo.zip"
            & git -c "safe.directory=$repo" -C $repo archive --format=zip -o $pacote $ref
            if ($LASTEXITCODE -ne 0) { throw "git archive falhou: $repo" }
            Expand-Archive -LiteralPath $pacote -DestinationPath $fonte
            & (Join-Path $raiz 'mvnw.cmd') -B -ntp -f (Join-Path $fonte 'pom.xml') '-Dmaven.test.skip=true' package
            if ($LASTEXITCODE -ne 0) { throw "Falha de build: $modelo" }
            Registrar 'Tag de referencia empacotada' 'PASSOU' $ref
            Iniciar $modelo (Join-Path $fonte 'target/pedidosestoque-0.0.1-SNAPSHOT.jar')
            foreach ($nome in 'produto','estoque','pedido') { $urls[$nome] = $urls[$modelo] }
            Suite comum
            Suite diferencas
            Encerrar $modelo
        }
    }
    Registrar 'Execucao completa' 'PASSOU' $Modo
} catch {
    Registrar 'Execucao interrompida' 'FALHA' $_.Exception.Message
    throw
} finally {
    foreach ($nome in @($processos.Keys)) {
        $instancia = $processos[$nome]
        if (!$instancia.proc.HasExited) {
            [IO.File]::WriteAllText($instancia.sinal, 'stop')
            if (!$instancia.proc.WaitForExit(30000)) { $instancia.proc.Kill(); $instancia.proc.WaitForExit() }
        }
    }
}

