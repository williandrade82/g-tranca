# Telas do console de gerenciamento (web)

Interface **separada do app**, em React + TypeScript, para operar o jogo online (§18.7). Papéis: **leitor** (só consulta), **operador** (ações do dia a dia, sempre com motivo) e **administrador** (tudo). Acesso por login Google, só com e-mail na lista permitida. Em pt-BR, funciona em notebook, com teclado e contraste adequados.

| Tela | Conteúdo | Quem age |
|---|---|---|
| **Painel** | jogadores online, mesas ativas, partidas por hora, saúde de cada serviço (verde/amarelo/vermelho) e links para os painéis de métricas e logs | leitor+ |
| **Contas** | busca por nome ou e-mail; ficha (nome, métodos de login, datas, carteira, inventário, mesas recentes); **bloquear/desbloquear**, **encerrar sessões**; exportar/apagar dados (LGPD) | leitor vê; operador bloqueia; administrador apaga |
| **Mesas** | lista (id, anfitrião, estado, modo, aposta, critérios); detalhe com assentos; **encerrar mesa travada** (com motivo) | leitor vê; operador encerra |
| **Economia** | carteira e **livro-razão** de uma conta; **ajustar saldo ou booster** (valor e motivo obrigatórios); migrações da carteira local (quem migrou, quanto, quando) | leitor vê; operador ajusta |
| **Loja e configuração** | catálogo de boosters, **ofertas da loja** (quantidade e valor), ganho e presente diários, versão mínima do app, **modo manutenção** e mensagem aos jogadores; tudo **versionado** com histórico | administrador |
| **Equipe** | lista de e-mails permitidos e papel de cada um; adicionar, mudar papel, remover | administrador |
| **Auditoria** | tudo o que cada operador fez (quem, quando, o quê, por quê); só leitura, com filtros | leitor+ |

Regras de interface: ação destrutiva pede **confirmação e motivo**; toda listagem é paginada; erros dizem o que aconteceu e o que fazer. Regras do negócio: §18.5 a §18.7.
