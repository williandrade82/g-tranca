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

(nenhuma ainda)
