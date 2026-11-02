# Documentação (`docs/`)

Mapa desta pasta: o que é especificação viva (atualize **antes** do código), o que é plano de
trabalho e o que é material de apoio. Para as regras do jogo, a fonte da verdade é
[`definition.md`](../definition.md), na raiz do repositório.

## Especificações vivas

| Arquivo | Conteúdo |
|---|---|
| [telas-app.md](telas-app.md) | Telas, modais e glossário de componentes do **app Android**. |
| [telas-admin-web.md](telas-admin-web.md) | Telas do **console web** de gerenciamento do jogo online (§18.7). |
| [rules-traceability.md](rules-traceability.md) | Relação entre cada seção de `definition.md` e os testes que a verificam. |
| [regrasflexiveis.md](regrasflexiveis.md) | Regras configuráveis por mesa: catálogo de variações da Tranca (§1-2) + o que foi implementado de fato, perfis Clássica/Paulista e estrutura do `RuleSet` (§3-5, reescritas em 2026-11-02). |
| [recrutamento.md](recrutamento.md) | Como as telas do multijogador local ficam sincronizadas entre aparelhos. |
| [seguranca-pendencias.md](seguranca-pendencias.md) | Falhas de segurança encontradas e aceitas como risco (não corrigidas na hora). |

## Planos de trabalho (`plano-*.md`)

Cada plano tem uma linha `Status:` no topo do próprio arquivo — é a fonte da verdade do status.
Um plano concluído ou substituído vai para `archive/`; os demais ficam aqui, ativos.

| Arquivo | Assunto | Status | Observação |
|---|---|---|---|
| [plano-online.md](plano-online.md) | Jogo online (microsserviços) | Em execução | Backend fase 1 pronto (`online/lobby-service`, `game-service`, `economy-service`); servidor em ajuste na branch atual `fix/online-servidor`. O app ainda não expõe o modo ao jogador. O próprio arquivo diz "nada implementado" — está desatualizado, mas este é o único plano genuinamente incompleto hoje. |
| [plano-multijogador-local.md](plano-multijogador-local.md) | Multijogador local (até 4 celulares, sem internet) | Quase concluído | Etapas 1 a 6 feitas (confirmado: módulos `:net` e `app/.../multiplayer` completos, protocolo v19); faltam 4 itens pontuais da etapa 6 e a etapa 7 (loja/privacidade + revisão final). |

Os dois planos abaixo **foram verificados como executados** (branch mesclada em `HEAD` + código real) e
foram arquivados — as linhas de `Status:` que diziam o contrário estavam desatualizadas:
[plano-moedas.md](archive/plano-moedas.md) e [plano-boosters.md](archive/plano-boosters.md). Ver
[archive/README.md](archive/README.md).

Nenhum plano está só "no papel" (futuro) neste momento — todos já têm pelo menos uma decisão
aceita ou código iniciado. **Lição:** a linha `Status:` de um plano pode ficar desatualizada se
ninguém a revisa conforme o código avança — ao classificar, confirme contra o código (branch
mesclada? classe/arquivo existe?), não só contra o texto do próprio plano.

## Arquivados (`archive/`)

Planos concluídos, substituídos por outro documento, ou que são apenas um retrato histórico
(não evoluem mais). Ver [archive/README.md](archive/README.md).

## Pastas de apoio

| Pasta | O que tem | Observação |
|---|---|---|
| [personagens/](personagens/) | Fichas de arte dos 4 robôs (texto) + as 12 folhas-fonte (`NN - perfil - nivel.png`) e as 2 imagens de conjunto. | **As imagens não foram movidas para `assets/`**: são entrada do pipeline `tools/cortar_personagens.py` / `tools/conferir_personagens.py`, que leem exatamente esta pasta. Mover exigiria atualizar os scripts e os testes (`CharacterAssetsTest`); mantido como está por ser dependência de build, não só documentação. |
| [loja/](loja/) | Rascunho da ficha da loja (Google Play) e da política de privacidade. | Nome/identificador corrigidos para Tranca-Bot; conteúdo e permissões atualizados para refletir o app atual (multijogador local, moedas, boosters). Ainda marcados para **nova revisão antes de publicar** — a seção de modo online fica incompleta até ele ser lançado. |
| [assets/](assets/) | Imagens, áudios e pacotes que não são entrada de nenhum script: `boosters/` (ícones, sons das reações, peças do painel da loja em `boosters/loja/`), `telas/` (capturas de tela citadas em `telas-app.md` e no plano arquivado de multijogador), e 4 imagens de divulgação dos robôs soltas na raiz de `assets/` (sem referência em nenhum documento atual). |
| [google-secrets/](google-secrets/) | Credenciais OAuth do Google. | Nunca versionado (`.gitignore`); não alterado nesta limpeza. |

Nomes de arquivo dentro de `assets/` seguem ASCII, minúsculas e hífen (sem espaço, acento ou
`_`); os poucos que já estavam assim não foram tocados.
