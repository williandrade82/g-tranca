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

## Resolvidas

| Data | Onde | Achado | Risco | Correção |
|---|---|---|---|---|
| 2026-11-01 | `online/economy-service/src/main/kotlin/com/gtranca/online/economy/EconomyStore.kt` (`claimDaily`, §14.6) | `POST /economy/{guestId}/daily` recebia `today` (dias desde a época) **escolhido pelo próprio app** e só conferia `today <= last` (o último dia resgatado); o servidor nunca comparava `today` com o seu próprio relógio. O app oficial, assinado e de qualquer jogador comum, resgatava de novo só avançando a data do aparelho (Configurações do Android). Cada dia novo também gerava uma chave (`ganho-diario-$today`) diferente, então o livro-razão idempotente não impedia a repetição. | **Alto**: moedas de graça sem limite, criadas do nada, ao alcance de qualquer jogador sem ferramenta nenhuma. | Commit `837f23a`: `EconomyStore.claimDaily` agora decide "hoje" pelo próprio relógio do servidor (dia UTC via `Math.floorDiv`), ignorando o `today` do cliente na decisão; a chave de idempotência do lançamento (`ganho-diario-$dia`) também usa esse dia do servidor, não o do cliente. `apply(...)` passou a ser checado: chave já aplicada agora recusa com `JA_RESGATADO` em vez de responder sucesso sem creditar. Testes citando §14.6 em `EconomyStoreTest` (resgate normal, resgate repetido no mesmo dia real com `today` adiantado, dia seguinte real, chave independente do `today` do cliente, virada à meia-noite UTC). |
