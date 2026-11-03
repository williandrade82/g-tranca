# Telas do console web de gerenciamento

Interface **separada do app Android** (ver [telas-app.md](telas-app.md)), em React + TypeScript, para operar o jogo online (§18.7). Papéis: **leitor** (só consulta), **operador** (ações do dia a dia, sempre com motivo) e **administrador** (tudo). Acesso por **e-mail e senha** (mesmo mecanismo do login do app, §18.1; login Google fica no backlog), só com e-mail na lista permitida, cadastrada por um administrador. Em pt-BR, funciona em notebook, com teclado e contraste adequados.

| Tela | Conteúdo | Quem age |
|---|---|---|
| **Entrar** | e-mail e senha; erro sem detalhar se o e-mail existe | qualquer membro da equipe |
| **Painel** | jogadores online, mesas ativas, partidas por hora, saúde de cada serviço (verde/amarelo/vermelho) e links para os painéis de métricas e logs | leitor+ |
| **Contas** | busca por nome ou e-mail; ficha (nome, métodos de login, datas, carteira, inventário, mesas recentes); **bloquear/desbloquear**, **encerrar sessões**; exportar/apagar dados (LGPD) | leitor vê; operador bloqueia; administrador apaga |
| **Mesas** | lista (id, anfitrião, estado, modo, aposta, critérios); detalhe com assentos; **encerrar mesa travada** (com motivo) | leitor vê; operador encerra |
| **Economia** | carteira e **livro-razão** de uma conta; **ajustar saldo ou booster** (valor e motivo obrigatórios); migrações da carteira local (quem migrou, quanto, quando) | leitor vê; operador ajusta |
| **Loja e configuração** | catálogo de boosters, **ofertas da loja** (quantidade e valor), ganho e presente diários, versão mínima do app, **modo manutenção** e mensagem aos jogadores; tudo **versionado** com histórico | administrador |
| **Equipe** | lista de e-mails permitidos e papel de cada um; adicionar, mudar papel, remover | administrador |
| **Auditoria** | tudo o que cada operador fez (quem, quando, o quê, por quê); só leitura, com filtros | leitor+ |

Regras de interface: ação destrutiva pede **confirmação e motivo**; toda listagem é paginada; erros dizem o que aconteceu e o que fazer. Quem não tem papel para uma ação **não vê o botão** (leitor não vê "Bloquear conta"/"Encerrar sessões" na ficha de Contas; qualquer papel vê "Exportar/Apagar dados" desabilitado com "Em breve", já que ainda não existe). Regras do negócio: §18.5 a §18.7.

**Bloquear/desbloquear conta e encerrar sessões** (03/11/2026, §18.7): bloquear só impede logar de novo e entrar em mesa nova, nunca ejeta de uma partida em andamento; encerrar sessões é ação independente do bloqueio. Uma conta **convidado** sem sessão ativa some da busca/ficha de Contas (ela só existe enquanto há sessão viva) — ao encerrar as sessões de um convidado, a ficha dele deixa de ser encontrável até ele entrar de novo; isso é esperado, não é um erro da tela.

**Mesas** (03/11/2026, §18.4/§18.7): leitor não vê o botão "Encerrar mesa" (mesmo padrão de Contas). Não há busca por texto na lista (o lobby-service não indexa isso) — a lista tem um campo "Abrir mesa pelo id" para ir direto a uma mesa específica. Encerrar uma mesa **EM_JOGO** mostra um aviso diferente de uma **ABERTA** vazia (a primeira devolve a aposta a todos os jogadores, §18.4); o id e os critérios da mesa aparecem sempre, mesmo quando ela tem um nome sorteado.
