# Arquivados

Planos concluídos, substituídos por outro documento, ou retratos de um momento específico do
código que não evoluem mais. Nada aqui orienta o desenvolvimento atual — ver [../README.md](../README.md)
para as especificações vivas e os planos ativos.

| Arquivo | Assunto | Por que foi arquivado |
|---|---|---|
| [plano-bots-personas.md](plano-bots-personas.md) | 4 robôs com personalidade (substituem os avatares por profissão) | Executado — concluído em 2026-10-17, entregue no PR #4. |
| [plano-nomes-aos-bots.md](plano-nomes-aos-bots.md) | Nomes e bordões fixos para cada robô | Executado — implementado em 2026-10-19 (commits `aeec398`, `2e4f13d`, `ceeffdf`). |
| [plano-tres-vermelhos.md](plano-tres-vermelhos.md) | Fechamento do 3 vermelho (início da vez, morto, cadeia, compra) | Executado — concluído em 2026-10-17, PR integrado. |
| [plano-revisao-visual-luxo.md](plano-revisao-visual-luxo.md) | Redesenho visual "luxo noturno de cassino" do app inteiro | Executado — falta só validar no celular. |
| [plano-loja.md](plano-loja.md) | Loja de boosters (trocar moedas por boosters) | Executado — implementada na branch `feature/loja`. |
| [plano-regras-flexiveis.md](plano-regras-flexiveis.md) | Regras configuráveis por mesa (perfis da casa) | Executado — `RuleSet` com `CLASSICA`/`PAULISTA` no `core-engine` e §14 completo em `definition.md`. O conteúdo real virou a seção 3 a 5 de [`../regrasflexiveis.md`](../regrasflexiveis.md); este arquivo fica só como registro do plano original. |
| [plano-moedas.md](plano-moedas.md) | Moedas locais e apostas contra os robôs | Executado — as 7 etapas do próprio arquivo estão marcadas "FEITA" e a branch `feature/moedas-locais` está mesclada em `HEAD`. A linha de status dizia "PR aberto": estava desatualizada. |
| [plano-boosters.md](plano-boosters.md) | Boosters (catálogo, ícones e sons das reações) | Executado — confirmado no código (`Boosters.kt`, `BoostersTest.kt`, telas de inventário/loja, protocolo multijogador v19 com `Loadout`) e a branch `feature/boosters` está mesclada. A linha de status dizia "nada implementado" e as etapas nunca foram marcadas: o arquivo simplesmente não foi atualizado durante o desenvolvimento. |
| [plano-pendencias.md](plano-pendencias.md) | Pendências do antigo "Mestre da Canastra" (nome anterior do app) | Quase todo executado — todas as etapas de código feitas; resta só a publicação na loja, que não é tarefa de código. |
| [plano-teamplay.md](plano-teamplay.md) | Pedido original do multijogador local | Substituído — virou [`../plano-multijogador-local.md`](../plano-multijogador-local.md), que é o plano ativo. |
| [comunicacao-extrato.md](comunicacao-extrato.md) | Raio-x do código de comunicação entre jogadores em 26/10/2026 (branch `feature/selecao-jogadores`, HEAD `51e00cc`) | Retrato de um ponto no tempo, não uma especificação — o próprio arquivo diz isso no topo. Pode estar desatualizado frente ao código atual. |
| [arquivo-avatares/](arquivo-avatares/) | Primeiro desenho dos avatares (16 profissões × 2 gêneros) | Abandonado em 2026-10-17 em favor dos 4 robôs — o próprio `LEIA-ME.md` da pasta já dizia isso. |
