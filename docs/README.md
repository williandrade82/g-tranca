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
| [regrasflexiveis.md](regrasflexiveis.md) | Regras configuráveis por mesa (perfis da casa, parâmetros de `RuleSet`). |
| [recrutamento.md](recrutamento.md) | Como as telas do multijogador local ficam sincronizadas entre aparelhos. |
| [seguranca-pendencias.md](seguranca-pendencias.md) | Falhas de segurança encontradas e aceitas como risco (não corrigidas na hora). |

## Planos de trabalho (`plano-*.md`)

Cada plano tem uma linha `Status:` no topo do próprio arquivo — é a fonte da verdade do status.
Um plano concluído ou substituído vai para `archive/`; os demais ficam aqui, ativos.

| Arquivo | Assunto | Status | Observação |
|---|---|---|---|
| [plano-online.md](plano-online.md) | Jogo online (microsserviços) | Em execução | Backend fase 1 pronto; servidor em ajuste na branch `fix/online-servidor`. O próprio arquivo ainda diz "nada implementado" — está desatualizado. |
| [plano-moedas.md](plano-moedas.md) | Moedas locais e apostas contra os robôs | Em execução | PR aberto, ainda não integrado. |
| [plano-boosters.md](plano-boosters.md) | Boosters (catálogo, ícones e sons das reações) | Em execução | O arquivo diz "nada implementado", mas a branch `feature/boosters` já tem os assets em `assets/boosters/` e a Loja (já executada) depende dela — **confirme se o código avançou mais do que o texto registra**. |
| [plano-multijogador-local.md](plano-multijogador-local.md) | Multijogador local (até 4 celulares, sem internet) | Em execução | Etapas 1 a 3 feitas; restante pendente. |
| [plano-regras-flexiveis.md](plano-regras-flexiveis.md) | Regras por mesa (perfis da casa Clássica/Paulista) | Executado, mas não arquivado | O `RuleSet` com `CLASSICA`/`PAULISTA` já existe em `core-engine`, e o §14 já está em `definition.md`. Mantido fora de `archive/` até decidir a fusão com `regrasflexiveis.md` (ver aviso no final da sessão). |

Nenhum plano está só "no papel" (futuro) neste momento — todos já têm pelo menos uma decisão
aceita ou código iniciado.

## Arquivados (`archive/`)

Planos concluídos, substituídos por outro documento, ou que são apenas um retrato histórico
(não evoluem mais). Ver [archive/README.md](archive/README.md).

## Pastas de apoio

| Pasta | O que tem | Observação |
|---|---|---|
| [personagens/](personagens/) | Fichas de arte dos 4 robôs (texto) + as 12 folhas-fonte (`NN - perfil - nivel.png`) e as 2 imagens de conjunto. | **As imagens não foram movidas para `assets/`**: são entrada do pipeline `tools/cortar_personagens.py` / `tools/conferir_personagens.py`, que leem exatamente esta pasta. Mover exigiria atualizar os scripts e os testes (`CharacterAssetsTest`); mantido como está por ser dependência de build, não só documentação. |
| [loja/](loja/) | Rascunho da ficha da loja (Google Play) e da política de privacidade. | Ainda citam o nome antigo do app, "Mestre da Canastra" — não corrigido aqui por ser conteúdo de produto, fora do escopo desta limpeza. |
| [assets/](assets/) | Imagens, áudios e pacotes que não são entrada de nenhum script: `boosters/` (ícones, sons das reações, peças do painel da loja em `boosters/loja/`), `telas/` (capturas de tela citadas em `telas-app.md` e no plano arquivado de multijogador), e 4 imagens de divulgação dos robôs soltas na raiz de `assets/` (sem referência em nenhum documento atual). |
| [google-secrets/](google-secrets/) | Credenciais OAuth do Google. | Nunca versionado (`.gitignore`); não alterado nesta limpeza. |

Nomes de arquivo dentro de `assets/` seguem ASCII, minúsculas e hífen (sem espaço, acento ou
`_`); os poucos que já estavam assim não foram tocados.
