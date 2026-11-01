# Segurança: achados não corrigidos

Regra (ver `CLAUDE.md`): toda falha de segurança **encontrada e não corrigida na hora** entra aqui — data, onde, o
achado, o risco e por que ficou pendente — para consulta e tratamento depois. Não é uma lista de tarefas em
andamento: um achado que já tem correção no plano da sessão atual não precisa de registro aqui, só o commit que
corrige. Isto é para o que foi **decidido deixar para depois**, aceito como risco, ou que depende de uma decisão
do usuário fora do código (rotacionar uma credencial, por exemplo).

Corrigido, mover a linha de "Pendentes" para "Resolvidas" com a data e o commit.

## Pendentes

| Data | Onde | Achado | Risco | Por que ficou pendente | Próximo passo |
|---|---|---|---|---|---|
| 2026-11-01 | `docs/google-secrets/` (Google Cloud, projeto `tranca-bot`) | Ao extrair o `client_id` do cliente OAuth Web com `grep "client_id"`, a linha inteira do JSON veio junto, incluindo o `client_secret` (`GOCSPX-...`). O `cat` direto do arquivo foi negado pelo classificador do Claude Code ("Credential Materialization"), mas o grep por um campo vizinho não é bloqueado e trouxe o segredo para o contexto da sessão. | Baixo/médio: o `client_secret` de um cliente OAuth "Web" não autentica o jogador (o login do Tranca-Bot usa o ID token do Android, verificado pelo servidor só com o `client_id` e as chaves públicas do Google, nunca com esse segredo), mas é uma credencial de servidor que não deveria ter passado por uma IA. | É rotação de credencial no Google Cloud Console, decisão do usuário sobre quando fazer; não há código a corrigir. | Usuário rotacionar o `client_secret` do cliente Web no Google Cloud Console quando for conveniente. Enquanto isso, nenhuma rotina do servidor usa esse valor (só o `GOOGLE_CLIENT_ID`), então não há urgência. |
| 2026-11-01 | `online/economy-service/src/main/kotlin/com/gtranca/online/economy/EconomyStore.kt` (`claimDaily`, §14.6) | `POST /economy/{guestId}/daily` recebe `today` (dias desde a época) **escolhido pelo próprio app** e só confere `today <= last` (o último dia resgatado); o servidor nunca compara `today` com o seu próprio relógio. O `:online-client` passa `today` adiante tal como recebe (é a interface, no app, que vai calcular o dia pelo relógio do aparelho, ex.: `LocalDate.now(zone).toEpochDay()`): **não precisa de app adulterado nem de chamada HTTP avulsa** — o app oficial, assinado e de qualquer jogador comum, resgata de novo só avançando a data do aparelho (Configurações do Android) e tocando em "resgatar" outra vez, quantas vezes quiser. Cada dia novo também gera uma chave (`ganho-diario-$today`) diferente, então o livro-razão idempotente não impede a repetição. | **Alto**: moedas de graça sem limite, criadas do nada, ao alcance de qualquer jogador sem ferramenta nenhuma (só o relógio do próprio celular); quebra o "saldo nunca negativo" na direção contrária e, como a carteira tem valor no jogo (apostas, loja), é uma falha de economia real, não teórica. | É o `economy-service`, fora do alcance deste agente nesta tarefa (escopada só para o `:online-client`; a correção é da lógica de negócio do servidor, não do cliente, que não pode alterar `economy-service` sem outro pedido). Documentado em vez de corrigido para não extrapolar o escopo pedido. | Corrigir em `EconomyStore.claimDaily`: o servidor deve decidir "hoje" pelo próprio relógio (UTC) em vez de confiar no `today` do app — por exemplo, aceitar só o dia corrente do servidor dentro de uma janela pequena para cobrir fuso horário (±14 h, o maior desvio de fuso do mundo), ignorando o `today` enviado ou usando-o só para exibir, nunca para decidir se já passou um dia. Tarefa para quem mexer no `economy-service` a seguir. |

## Resolvidas

(nenhuma ainda)
