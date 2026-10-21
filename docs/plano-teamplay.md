Quero adicionar ao G-Tranca um modo multijogador local: até 4 pessoas, cada
uma no seu celular, na mesma mesa, SEM internet. Antes de qualquer código,
leia CLAUDE.md, definition.md, docs/telas.md e os planos em docs/ para
seguir o mesmo padrão. Imagem de referência da tela de posição:
docs/telas/escolha-posicao-multiplayer.png.

## Arquitetura desejada
- Um celular HOSPEDA a mesa e é a autoridade: roda o GameController e o
  :core-engine, que conhecem as cartas de TODOS (necessário para
  desistência, abandono e queda). Os outros ENTRAM na mesa.
- Convidados só enviam a jogada pretendida (Action) e eventos de sala. O
  anfitrião valida com legalActions, aplica com apply() e transmite.
- Toda mesa tem 4 assentos. Assentos sem humano têm robôs; os personagens
  dos robôs e a dificuldade são escolhidos pelo anfitrião antes do início. O
  anfitrião também joga. A semente e o RNG ficam só no anfitrião.
- O que cada celular recebe: tudo o que é visível na mesa daquele assento
  (a própria mão, tamanhos das mãos, lixo inteiro, jogos baixados dos dois
  lados, 3 vermelhos, última jogada de cada jogador, placar, de quem é a
  vez, situação dos mortos). NUNCA: mão dos outros, cartas do monte, cartas
  dos mortos, semente, GameState/Match completo. As regras da mesa (perfil
  de regras, pontuação-alvo, aposta) vão UMA vez, ao definir posições e
  regras, antes do início.
- Cada jogada processada pelo controlador é enviada às telas, que apenas a
  processam. O controlador só pára quando precisa de interação humana. Cada
  aparelho executa a PRÓPRIA animação, na velocidade escolhida pelo seu
  jogador; não há sincronização de animação entre aparelhos.
- Transporte atrás de uma interface (ex.: LocalTransport), com:
  1. implementação em memória para testes JVM (anfitrião e convidados no
     mesmo processo, sem Android);
  2. implementação real Android com Nearby Connections (P2P_STAR).
- Mensagens com kotlinx.serialization (Action já é @Serializable) e número
  de versão do protocolo: versões diferentes → recusar com mensagem clara.
- Crie o módulo :net (Kotlin/JVM puro, depende só de :core-engine) para
  protocolo, sessão e transporte em memória; o que for Android fica em :app
  (ou :net-android, se justificar no plano). Respeite a regra de dependência.

## Decisão: tecnologia do transporte
Nearby Connections, estratégia P2P_STAR (1 anfitrião + até 3 convidados),
que funciona sem internet e escolhe sozinha entre Bluetooth, BLE e Wi-Fi.
Aparelhos sem Google Play Services ficam fora por enquanto (aceito). O
transporte continua atrás da interface, para trocar depois sem mexer no
resto do código. Documente no plano as permissões (Bluetooth e
NEARBY_WIFI_DEVICES no Android 13+, localização nas versões antigas).

## Fluxo da mesa (telas; atualize docs/telas.md ANTES do código)
1. Mesas disponíveis: hoje lista 4 mesas aleatórias de 2 ou 4 jogadores.
   Perto do título entra um botão novo, com o símbolo de Wi-Fi. Só quando
   ligado ele pesquisa mesas abertas nas proximidades e as acrescenta à
   lista atual; desligado, nada de rede é usado.
2. Cada mesa multijogador na lista mostra: nome da mesa (definido pelo
   anfitrião), dificuldade em estrelas, tipo de regras, aposta de cada um em
   moedas e o botão "Ver regras" como hoje nas mesas de duplas. No lugar da
   imagem do personagem, um banner animado escrito "Multi" na primeira
   linha e "player" na segunda. Mostra também os cards "Dupla 1" e "Dupla 2",
   cada um com dois nomes: o jogador já conectado ou "Vago" quando o assento
   estiver livre. Mesa em andamento aparece com a marca "jogo em andamento".
3. Criar mesa (anfitrião): define nome da mesa, perfil de regras,
   pontuação-alvo, aposta, dificuldade e personagens dos robôs ANTES de
   publicar. Depois escolhe a própria posição e publica.
4. Ao tocar na linha da mesa abre a tela "Escolha sua posição" (ver imagem):
   mesa redonda com os 4 assentos; os botões "rotacionar à esquerda" e
   "rotacionar à direita" giram as posições na tela e o jogador sempre cai
   num assento VAGO (o inicial também é um assento vago). O jogador sempre
   joga na posição de baixo da tela. O nome do avatar é um campo editável,
   iniciado com o nome do perfil (sem foto; cor do baralho não trafega, cada
   um usa a do próprio perfil). "Confirmar posição" vira "Aguardando início
   do jogo" e a tela continua recebendo atualizações. Cada alteração é
   enviada ao anfitrião e redistribuída a todos, que atualizam suas telas.
   Um botão "sair" fica disponível o tempo todo.
5. Na mesa: indicador de vez também para humanos remotos. Use os componentes
   de ui/theme (GButton, GPanel, GMedallion...) e o padrão visual atual.

## Regras decididas (use como base do definition.md; não reabra)
- Em duplas, humanos podem ser parceiros, adversários ou ambos, conforme o
  assento escolhido.
- Moedas e apostas valem em grupo. O saldo é local e não verificável; o
  risco de adulteração é aceito por ora (futuramente tratamos a segurança).
- Entrada no meio do jogo: uma pessoa pode ocupar o lugar de um robô ou de
  quem saiu, mas o anfitrião precisa permitir. Quem entra herda a aposta já
  retida do jogador que saiu (nada é debitado de novo).
- Identidade: cada jogador recebe um TOKEN que o identifica e permite
  reingressar no mesmo assento. Todos os participantes conhecem a
  identificação (id, nome, ordem de entrada) de todos. Nomes repetidos entre
  humanos são permitidos.
- Queda de conexão: o jogo pausa quando chega a vez de quem caiu; a mesa
  espera o mesmo jogador. O anfitrião escolhe entre:
  1) aguardar o reingresso: a tela de espera fecha quando ele reconecta e o
     jogo segue. Se ele desistir, o anfitrião é avisado, esta opção fica
     desativada e a opção 3 é ativada na hora;
  2) substituí-lo por um robô: efeito imediato, enviado a todos, a jogada
     continua;
  3) recrutar outro jogador: a mesa volta à lista com "jogo em andamento"; o
     jogo fica pausado para todos, que são informados das ações do
     anfitrião; o anfitrião vê "aguardando recrutamento" e ainda pode
     substituir por robô. Quem entra ocupa exatamente o assento da vez e já
     entra jogando, como num jogo pausado que acaba de abrir.
  O jogador que caiu pode reingressar enquanto o assento não for
  substituído ou ocupado.
- Desistência (Voltar do Android, ou bandeira + confirmação): o jogador é
  substituído na hora por um robô e perde a aposta, que fica com o robô. Se
  a dupla do robô (ou o robô) vencer, o prêmio vai para o outro humano da
  dupla; se não houver outro humano que possa receber, vai para o dono da
  mesa. A desistência encerra só para quem desistiu; o jogo segue.
  Reescreva §13.1 e §14.6 para o jogo em grupo sem alterar o individual.
- Anfitrião que desiste pode passar o cargo (ver "Sucessores do anfitrião") a outro humano (nunca a robô); o
  escolhido é avisado, e a desistência do antigo anfitrião vira a de um
  jogador comum. A sucessão segue a ordem de entrada e a disponibilidade de
  comunicação. Se o anfitrião desconectar, deixa de ser anfitrião e reconecta
  como jogador comum.
- "Continuar" vale só para outros jogadores, não para o anfitrião.
  Estatísticas valem para todos os jogadores.
- Sem limite de tempo por jogada na primeira versão.

## Sucessores do anfitrião (decidido; detalhe no plano)
- O 1º e o 2º sucessores (ordem de entrada e disponibilidade de comunicação)
  recebem e mantêm ATUALIZADO, o tempo todo, o estado completo da mesa
  (Match, incluindo mãos, monte, mortos e semente), para assumir se o
  anfitrião cair ou desistir. É a ÚNICA exceção à regra de nunca enviar
  cartas ocultas: os demais convidados nunca recebem isso.
- Pergunta técnica que o plano deve responder: canal seguro resolve? Minha
  análise: não resolve o problema de lógica, porque o risco está no aparelho
  de destino (o sucessor precisa decifrar para assumir), não no caminho. O
  Nearby já cifra a conexão. Portanto, ajustar a regra com a exceção, com
  estas salvaguardas: o estado do sucessor fica só em memória (nunca em
  disco, log ou tela), é descartado ao fim do jogo ou quando deixa de ser
  sucessor, e o app nunca o exibe. Um endurecimento futuro (estado cifrado
  com chave só liberada na passagem) fica fora do escopo; avalie e registre
  no plano como ideia futura, no mesmo espírito do risco das moedas.
- O anfitrião só considera uma jogada confirmada depois que o 1º sucessor
  confirmou o recebimento da atualização (ou use outra estratégia que o plano
  justifique), para uma queda abrupta não perder jogadas.
- Ao assumir, o sucessor inicia um GameController a partir do Match
  recebido, avisa a todos e o jogo continua. O antigo anfitrião, se voltar,
  reingressa como jogador comum (token).
- Atualize no definition.md a regra de informação oculta com essa exceção, e
  o agente rules-auditor deve conferir que só os sucessores a recebem.

## Salvamento e "Continuar" (decidido)
O anfitrião não retoma como anfitrião: ao desconectar ou desistir ele deixa
de ser anfitrião. A opção "Continuar" pode aparecer para quem era anfitrião,
mas ele volta à mesa como jogador comum, reingressando com o token. O
aparelho de um jogador comum guarda só a sessão (mesa, assento, token, nome),
nunca o Match. O aparelho que é anfitrião ou sucessor não grava o estado
completo em disco para retomada.

## Mesa sem outros humanos (decidido)
Se todos os demais jogadores desistirem, as moedas vão todas para o
anfitrião caso ele ganhe. Se ele perder, mesmo para robôs, perde só a quantia
que apostou. Reescreva o §14.6 incluindo esse caso.

## Riscos e pontos a resolver no plano (não escolha sozinho; proponha)
1. Tratar o GameController: hoje aceita no máximo um HumanPlayer, um
   viewerSeat, um humanRequest e monta um GameSnapshot para um espectador;
   além disso espera a encenação dos 3 vermelhos (awaitingPresentation).
   Planeje a refatoração para vários humanos, um pedido por assento e um
   fluxo de eventos por jogada, sem mudar o jogo contra bots. Faça essa
   etapa ANTES do transporte, com os testes atuais passando e testes de
   regressão (mesma semente, mesmo resultado).
2. Salvamento: ver a seção acima; confirmar que nada do Match vai para disco nos convidados.
3. Reconexão e robustez: token por jogador, número de sequência e
   idempotência, jogada fora da vez recusada, mensagens perdidas ou
   reordenadas, reingresso com a mesma mesa. Testes importantes, rápidos e
   sem custo extra no laço do jogo (relógio virtual, sem delay real).
4. Permissões: pedir só ao entrar no modo multijogador (ao ligar o botão de
   Wi-Fi ou criar mesa), com explicação ao usuário; depende da tecnologia
   escolhida.
5. Moedas: isole o código das apostas de grupo para endurecer depois.

## Primeiro passo: plano + perguntas (NÃO escreva código ainda)
Crie docs/plano-multijogador-local.md no formato dos outros planos
(objetivo, restrições, decisões, etapas com um commit cada). Faça-me só as
perguntas que ainda não estão respondidas acima (dúvidas que encontrar
ao ler o código e o definition.md). Pare e espere minhas respostas.

Depois das respostas, atualize definition.md (nova seção ou §14, e §13.1 e
§14.6) e docs/telas.md ANTES do código, com testes citando as seções.

## Etapas (um commit por etapa; pare para eu revisar ao fim de cada uma)
1. definition.md e docs/telas.md com as regras e as telas do multijogador.
2. Refatorar o GameController local: vários humanos, fluxo de eventos por
   jogada, snapshot por assento; testes de regressão contra bots.
3. Protocolo + sessão + LocalTransport em memória + testes JVM: partida
   completa com humanos simulados + robôs; nenhum convidado recebe carta
   alheia (varrer a mensagem serializada); jogada ilegal, fora da vez ou
   duplicada recusada; resultado igual ao de uma partida local com a mesma
   semente; versões de protocolo diferentes recusadas.
4. Reconexão com token, queda na vez, substituição por robô, recrutamento,
   entrada no meio do jogo, desistência com repasse da aposta e passagem de
   anfitrião (tudo na JVM, com transporte em memória).
5. Transporte real no Android (tecnologia decidida), permissões e descoberta.
6. Telas: botão de Wi-Fi na lista, linha da mesa multijogador, criar mesa,
   escolha de posição, sala de espera, telas de queda/recrutamento do
   anfitrião, indicador de vez remoto.
7. Atualizar docs/loja (ficha e política de privacidade) com as novas
   permissões, deixando claro que nada sai do aparelho e que não há
   internet.

## Regras de trabalho
- Agentes: net-comm-dev para protocolo, sessão e transporte (:net e a parte
  Android do transporte); rules-engine-dev para mudanças no motor (se
  houver); android-ui-dev para telas, GameController, ViewModels e :data;
  rules-auditor ao final para checar vazamento de informação oculta.
- Não altere regras do jogo individual nem o fluxo atual contra bots: o
  multijogador é uma opção nova na tela de mesas disponíveis.
- Rode ./gradlew.bat test e :app:assembleDebug ao fim de cada etapa.
- Da etapa 5 em diante, diga-me o que testar em 2 a 4 celulares reais (o
  emulador não simula bem Bluetooth/Nearby/Wi-Fi direto).
