# Plano: jogar online (pela internet), em microserviços

Status: **versão 2, com as decisões do usuário de 28/10/2026**; nada implementado. Fluxo de sempre: `definition.md` e `docs/telas.md` primeiro, depois testes, código, `rules-auditor`, um commit por etapa, APK com hash. Agentes: `online-backend-dev` (serviços do jogo) e `admin-console-dev` (interface de gerenciamento, backend e frontend).

> **"Ficar online" = jogar com outras pessoas pela internet.** Publicar na Play Store é outra frente (lista em `docs/loja/` e na memória do projeto) e entra só como fase final.

## Princípio inegociável: o online é um adicional
Tudo o que existe para **jogadores próximos** (multijogador local por Nearby, §16, e o jogo contra robôs) **continua exatamente como está**. Por isso:
1. **Não se reescrevem rotinas existentes.** Antes de reaproveitar uma peça (`:net`, `GameController`, `HostSession`...), a gente a **lê e entende**; se não servir sem mudança, **cria-se uma nova** ao lado. Nada de "refatorar para servir aos dois".
2. **Código novo em lugares novos:** `online/` (serviços, build Gradle próprio, fora do `settings.gradle.kts` do app) e um módulo Android novo `:online-client` (só ele conhece a rede online). `:core-engine`, `:ai`, `:net`, `:sim` e `:data` **só são lidos**; mudar algum deles exige pedir ao usuário, com o motivo.
3. **Pontos de contato com o app, pequenos e aditivos:** o botão **Jogar online** no Início, a rota das telas online, o campo temporário do servidor no Perfil e as strings novas. O resto do app não muda.
4. **Documentação:** o online ganha a **§18** no `definition.md` e as telas novas em `docs/telas.md`; §14 a §17 não são reescritas (no máximo uma linha de remissão).
5. **Testes de não regressão:** toda fase termina com `./gradlew.bat test` do app verde, sem tocar nos testes existentes.

## Decisões do usuário (28/10/2026)
| # | Decisão |
|---|---|
| 1 | **Servidor dedicado** para o online; o **celular anfitrião** (Nearby) continua para amigos próximos. |
| 2 | Mesa **só por convite** no começo: a **chave da mesa** é distribuída pelo anfitrião por **QR Code** na tela dele ou por **link** para enviar por mensagem. O radar público fica para depois (ver "Mesas públicas"). |
| 3 | **Login só com Google** no começo; e-mail e senha entram depois, **se a arquitetura permitir sem retrabalho** (ver "Identidade"). |
| 4 | A **carteira local migra** para o online (ver dúvida sobre as regras da migração). |
| 5 | **Critérios da mesa:** tempo de jogada **indeterminado, 1 min ou 5 min**; punição de abandono: **perde a aposta**, com **devolução se sair nos primeiros 60 s**. |
| 6 | Por ora roda numa **rede local, em Kubernetes**; depois migra para uma **nuvem a definir**. O Perfil ganha um **campo temporário** (gravado só no aparelho) com o **IP ou hostname** do serviço online. Preferência por **software que se possa rodar localmente** (auto-hospedável), para depois contratar o serviço profissional equivalente no data center. |
| 7 | Quer **explicação** das opções de mesas públicas (abaixo). |

### 2ª rodada de respostas (28/10/2026)
| # | Decisão |
|---|---|
| 1 | Confirmado: servidor dedicado para o online; celular anfitrião (Nearby) segue só para amigos próximos, sem se misturarem. |
| 2 | Migração da carteira **sem teto**, cópia única por conta; poucos usuários, a revisão fica para a interface de gerenciamento. |
| 3 | Tempo esgotado = **abandono** (nunca se joga pelo jogador). |
| 4 | Punição tem as opções **sem punição** e **perde a aposta** (devolvida se sair nos primeiros 60 s). |
| 5 | **O app não lê QR Code.** O QR e o link abrem o app pela **câmera/mensageiro padrão** (ver "Link e QR"). Sem usar link, o jogador configura o servidor e **digita o id** da mesa (6 caracteres). |
| 6 | TLS na rede local: explicado ao usuário; recomendação adotada provisoriamente (texto puro só em build de teste; TLS no uso real). |
| 7 | Chave de assinatura (keystore): fica para quando for preciso. |
| 8 | Console de gerenciamento em **React + TypeScript**. |
| 9 | Login do console com **lista de e-mails** e papéis **leitor, operador e administrador**. |

Fase 0 escrita: `definition.md` §18, `docs/telas.md` (3.21 a 3.24, campo no Perfil e mapa) e `docs/telas-gerenciamento.md`.

### Link e QR (como abrir o app pela câmera padrão)
- O QR e o link são **endereços https** do próprio serviço: `https://<servidor>/m/<id>`. A câmera do celular (ou o toque no link numa mensagem) abre o navegador nessa página, **servida pelo `lobby-service`** atrás do ingress.
- A página tenta abrir o app (`tranca://mesa/<id>?s=<servidor>`) e mostra um botão **Abrir no Tranca-Bot**, o **id da mesa** para digitar e, se o app não estiver instalado, o aviso para instalar.
- O app declara o esquema `tranca://` (e só ele) e trata o `id` e o `servidor` recebidos; **nenhum** filtro genérico de https, para o app não aparecer em links de outros sites.
- **Depois, com domínio próprio e HTTPS na nuvem:** passa a valer **App Links verificados** (arquivo `assetlinks.json`), e o link abre o app direto, sem página intermediária. Isso **não** funciona em rede local sem domínio, por isso a página intermediária agora.
- Se o jogador **não** tiver o servidor configurado, o `s=` do link preenche o campo temporário (com a confirmação dele).

## Arquitetura em microserviços
Cada serviço tem **um dono de dados** (banco/esquema próprio), fala com os outros por **HTTP/gRPC** (consulta) e por **eventos** (fatos), e empacota-se em **imagem de contêiner** com *health checks* e configuração por variáveis de ambiente (12-factor): é isso que torna a mudança de "cluster local" para "nuvem" só uma troca de configuração.

| Serviço | Responsabilidade | Dono dos dados |
|---|---|---|
| **ingress** (Traefik ou NGINX, pronto) | entrada única, TLS, roteamento, WebSocket, limite de taxa | — |
| **identity-service** | login com Google (valida o *ID token*; depois e-mail/senha), contas, perfil público (nome, foto), emissão e renovação de tokens | contas, credenciais |
| **economy-service** | **carteira, inventário, loja, ganho diário, presente diário** e a **migração** da carteira local; livro-razão idempotente (a mesma ideia do `ShopRepository`, agora no servidor) | saldo, boosters, lançamentos |
| **lobby-service** | mesas: criar, **chave/QR/link**, regras e critérios da mesa, assentos, chamada de amigos, estado "aberta/em jogo/encerrada" | mesas, convites |
| **game-service** | **a partida autoritativa**: roda o `:core-engine`, bots do `:ai`, WebSocket com os celulares, relógio de jogada, abandono, reconexão; ao fim publica o resultado | só memória (e o resultado ao terminar) |
| **admin-api** + **admin-web** | gerenciamento (agente `admin-console-dev`) | auditoria, configurações |

**Infra compartilhada (toda auto-hospedável):** PostgreSQL (um esquema por serviço), **NATS** (mensageria de eventos; leve, roda em qualquer lugar), Prometheus + Grafana + Loki (métricas, painéis, logs), **Helm** (um *chart* por serviço), registro de imagens do cluster. Na nuvem cada peça tem um equivalente gerenciado (Postgres gerenciado, NATS/Pub-Sub, etc.).

**Código compartilhado:** uma biblioteca `online/contracts` (Kotlin, `@Serializable`) com os DTOs e o protocolo do online, **versionada**; depende de `:core-engine` só para tipos (`Card`, `Action`, `PlayerView`...), sem alterá-lo. O app usa o mesmo `contracts` pelo `:online-client`.

### Fluxo de uma partida
1. O anfitrião cria a mesa no **lobby-service** (regras, aposta, critérios) e recebe a **chave**; o app mostra o **QR** e o **link** (`tranca://mesa/<chave>?s=<servidor>`).
2. Cada amigo abre o link ou lê o QR, entra com a conta, ocupa um assento; o lobby confere o **saldo** no **economy-service**.
3. O anfitrião inicia: o lobby pede ao **game-service** uma partida; o economy **retém as apostas**.
4. Os celulares conectam ao game-service por WebSocket; só trafega `Action` para cima e `PlayerView` para baixo (nunca `GameState`).
5. Fim do jogo: o game-service publica `partida.encerrada`; o economy **liquida** (rateio de `Coins`, §14.6) de forma idempotente.

### Identidade (decisão 3)
- **Google primeiro:** o app obtém o *ID token* pelo Credential Manager (Android) e o **identity-service o valida** (assinatura e `aud`) e emite os tokens do jogo. Funciona numa rede local, só precisa de saída para a internet (para as chaves públicas do Google).
- **E-mail/senha depois, sem retrabalho:** o identity-service guarda **contas** separadas de **métodos de login** (Google, senha); acrescentar senha é um método novo (hash Argon2, verificação de e-mail, recuperação). Não precisa implementar os dois já no começo.
- Alternativa avaliada e **não** escolhida: Keycloak. Ele resolve os dois métodos, mas o fluxo de redirecionamento do Google não aceita IP/hostname de rede local como endereço de retorno; o ID token direto do Android evita o problema.

### Mesa: critérios (decisão 5)
- **Tempo de jogada:** `INDETERMINADO`, `1 MINUTO` ou `5 MINUTOS`, escolhido pelo anfitrião ao criar a mesa; imutável depois (como as regras, §14.4); aparece no convite, como a aposta.
- **Punição de abandono:** quem **sai** perde a aposta; se sai **nos primeiros 60 s** do jogo, a aposta é **devolvida**. Um robô assume o assento (§16.9). *Pontos em aberto abaixo.*

### Moedas e migração (decisão 4)
A carteira local é adulterável e "aceita". Para o online isso é um risco real (moedas viram vantagem sobre outros). Proposta de proteção, **sem tirar nada do jogo offline**: migrar **uma vez por conta** (cópia, não mudança do local), com **teto** definido pelo usuário e marca no servidor de que já migrou. Detalhes nas dúvidas.

### Mesas públicas (decisão 7, ver explicação)
Desenhar já o campo `visibilidade` da mesa (`PRIVADA` | `PUBLICA`) mas **implementar só `PRIVADA`**; o radar público entra depois sem refazer nada.

## Rede local e nuvem (decisão 6)
- **Hoje:** cluster Kubernetes na rede local (k3s/kind/minikube ou o que você já tem), acesso por IP ou hostname. O app lê o **servidor** de um campo temporário do Perfil (`online_server`, só no aparelho, não vai para a nuvem de ninguém).
- **Transporte seguro:** o Android bloqueia tráfego sem TLS por padrão. Para o laboratório, permitir `ws://`/`http://` **só** para o host configurado (configuração de segurança de rede em *build* de desenvolvimento); para uso real, TLS (`wss`) com certificado (cert-manager, com autoridade local agora e Let's Encrypt/da nuvem depois).
- **Depois (nuvem):** a mesma `Helm chart` com outros valores; o campo temporário do Perfil vira o endereço fixo do app e some da interface.
- **Regra de escolha de software:** quando houver um serviço que existe como produto remoto **e** pode rodar local (Postgres, NATS, Grafana, Traefik, MinIO...), usar a versão local agora; na migração, trocar pelo equivalente profissional.

## Fases (cada uma termina jogável e com um critério de saída)
| Fase | Entrega | Critério de saída |
|---|---|---|
| **0. Docs e decisões** | `definition.md` §18, telas novas, respostas às dúvidas | usuário aprova |
| **1. Esqueleto e jogo** | `online/` (build, contracts, Helm, Postgres, NATS), **game-service** e **lobby-service** com conta anônima, mesa por chave/QR/link, 1×1 e duplas, robôs, reconexão, critérios da mesa; `:online-client` e telas mínimas | 2 e depois 4 celulares jogam um jogo inteiro por Wi-Fi do cluster, sem moedas; `:sim` joga 1000 partidas pelo servidor sem estado ilegal |
| **2. Contas e economia** | **identity-service** (Google), **economy-service** (carteira, apostas, loja, presente), migração da carteira, punição de abandono | compra e aposta sobrevivem a reinstalar o app; saldo nunca negativo sob concorrência |
| **3. Console de gerenciamento** | **admin-api** e **admin-web** (contas, mesas, economia, configurações, auditoria) | operador bloqueia uma conta e ajusta um saldo, com registro |
| **4. Amigos e presença** | lista de amigos, "online agora", convite direto, push (FCM) | convidar um amigo com o app fechado |
| **5. Endurecimento e nuvem** | observabilidade, backup, LGPD (exportar/apagar), migração para a nuvem, Play Store | beta fechado por 2 semanas |
| **6. (futuro) Mesas públicas** | radar, denúncia, bloqueio, moderação | decidir depois do beta |

## Riscos
1. **Interferir no que já funciona.** Mitigação: o princípio acima, pontos de contato mínimos e `./gradlew.bat test` a cada etapa.
2. **Muitos serviços para uma pessoa operar.** Mitigação: começar com **três** (lobby, game, economy+identity juntos se preciso) e separar quando houver motivo; manter o contrato entre eles desde o início.
3. **Latência e queda de rede:** a mesa tem pausa e reconexão no §16; o tempo de jogada resolve mesa parada.
4. **Versões:** protocolo versionado; serviço aceita a versão atual e a anterior por um tempo.
5. **Carteira migrada:** abre caminho para criar moedas offline e levá-las ao online (ver dúvidas).
6. **Chave de assinatura (keystore):** o login Google do Android exige registrar a impressão digital (SHA-1) do app; o certificado de *debug* serve nos testes, mas a chave definitiva precisa existir antes de publicar.

## Pontos provisórios (marcados no `definition.md` §18)
- **Queda de conexão** (§18.4): com tempo de jogada, a vez continua contando; no indeterminado a mesa espera e o anfitrião pode trocar o jogador por robô.
- **Migração:** o inventário de boosters migra junto com a carteira?
- **Papéis do console** (§18.7): divisão leitor/operador/administrador proposta, a confirmar.
- **Início dos 60 s:** contados desde o início da 1ª partida do jogo.
