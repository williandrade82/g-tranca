# Recrutamento imediato: máquina de estados e sincronização (§16.2, §16.15)

Fonte da verdade das regras: `definition.md` §16.2 e §16.15. Este documento descreve **como** a tela "Iniciar novo jogo"
fica sincronizada entre aparelhos. Código: `net/.../Recruitment.kt`, `PresencePhotos.kt`, `app/.../NearbyTransport.kt`.

## Princípios

1. **O anúncio de presença só descobre; a conexão decide.** O anúncio leva o essencial e muda pouco: chave, nome,
   `hosting` (a montagem da mesa está aberta: quem estiver livre abre uma conexão de **espera**), `inviting` (reserva: chaves
   marcadas que ainda não têm conexão) e `recruitedBy` (anfitrião que já recrutou o jogador, para os terceiros mostrarem
   "Recrutado"). Todo o resto (convite, cancelamento, aceite, recusa,
   montagem, saída, remoção, início) vai por **mensagens na conexão** do recrutado com o anfitrião (`ReliableLink`:
   ordem, confirmação e reenvio).
2. **Nada se decide pela ausência momentânea na busca.** A presença que some e volta (anunciante parou e reanunciou, busca
   reiniciada) não cancela convite, não desmarca e não tira ninguém da lista: ver "Lista de presença".
3. **O estado sobrevive** à recomposição da tela e ao reinício da presença: o `Recruitment` vive enquanto o Wi-Fi do jogo
   está ligado (chave de presença) e é o mesmo objeto para a tela e para o transporte.
4. **Montagem versionada:** o anfitrião envia sempre a montagem **completa** com um número de versão crescente; o
   recrutado aplica só versão maior que a aplicada.

## Papéis e estados

### Anfitrião (por marcado)
`Pendente` (marcado, sem resposta) -> `Aceito` (conectado) -> fora.

| Evento | Efeito |
|---|---|
| montagem aberta (`setHosting`) | anuncia `hosting`; os jogadores próximos livres conectam (espera), no máximo 6 |
| marcar | cria o marcado `Pendente`. **Se o jogador já está em espera**: `RecruitInvite` pela conexão na hora (sem mexer no anúncio). Senão: reserva, a chave vai em `inviting` e o convite sai quando ele conectar |
| recrutado conecta e envia `RecruitHello` | se `Pendente`: responde `RecruitInvite` (nome do anfitrião + montagem); se `Aceito` sem conexão (reingresso): troca a conexão e reenvia `RecruitSync` completo; se não marcado: `RecruitEnd(CANCELLED)` |
| `RecruitReply(aceito)` | `Aceito`; sai de `inviting`; envia a montagem completa |
| `RecruitReply(recusado)` | remove; aviso ao anfitrião com o motivo |
| desmarcar | `Pendente`: `RecruitEnd(CANCELLED)` (se já conectado) e sai de `inviting`; `Aceito`: `RecruitEnd(REMOVED)` |
| `RecruitLeave` | remove; aviso (sair da mesa, ou saldo insuficiente) |
| conexão cai (`Aceito`) | marcado fica `Aceito` **sem conexão** por 8 s à espera do reingresso; passado o prazo, remove com aviso "fora do alcance" |
| presença de `Pendente` ausente da lista por 10 s seguidos | remove com aviso "fora do alcance" |
| mudança da montagem | espera 0,3 s sem novas mudanças (debounce), incrementa a versão e envia a montagem completa a todos os `Aceito` conectados |
| Escolher posições | `RecruitStart(tableId)` a cada `Aceito` conectado (espera as confirmações até 2 s); `Pendente` vira robô; a mesa é aberta depois |
| sair / Wi-Fi desligado | `RecruitEnd(CLOSED)` a todos os conectados, espera até 0,5 s pelas confirmações |

### Recrutado
`Livre` -> (vê um anfitrião com `hosting`, ou a própria chave em `inviting`) **Em espera** (interno; a tela mostra
`Livre`) -> (recebe `RecruitInvite`) `Convidado` -> (Aceitar) `Aceito` -> fim.

- **Em espera:** conecta ao anfitrião e envia `RecruitHello` e a foto; a conexão fica aberta, sem custo de anúncio. Falha:
  nova tentativa depois de 0,3, 1, 2, 4 s (e a cada 8 s) enquanto o anfitrião continuar montando a mesa ou me listar.
  Só uma espera por vez (STAR: um aparelho é "spoke" de um só centro); outro anfitrião que me liste em `inviting` toma o
  lugar da espera. O anfitrião que parou de montar a mesa solta a espera. Dois aparelhos com a montagem aberta: só o de
  **menor chave** abre a conexão (a conexão serve aos dois sentidos).
- **Convidado**: o convite não tem prazo. `RecruitEnd(CANCELLED)` ou queda da conexão o desfaz. Reanunciar a presença
  com `recruitedBy` (pendente ou aceito) marca "Recrutado" para os terceiros.
- **Aceito**: recebe `RecruitSync` (versão maior que a aplicada). `RecruitEnd` (`REMOVED` = "tirou você da mesa";
  `CLOSED` = "Mesa encerrada"), `RecruitStart` (entra na mesa com a própria chave) ou saída própria encerram. **Queda da
  conexão** sem aviso: continua `Aceito` por até 8 s tentando reconectar (`RecruitHello` de reingresso ao anfitrião,
  achado pela chave dele); o anfitrião reenvia a montagem completa. Passado o prazo: `Livre` + aviso "Mesa encerrada".
- **Início perdido:** se a mensagem de início não chegou (queda no momento do toque), a mesa do anfitrião aparece na
  busca com a chave do recrutado em `invited`: ele trata como início e entra.

### Terceiro (outro anfitrião)
Vê o jogador com `recruitedBy` de outra chave como "Recrutado" (não selecionável). `recruitedBy` só existe enquanto o
jogador está `Convidado` ou `Aceito`; recusa/saída/remoção o limpam.

## Lista de presença (NearbyTransport)

| Situação | Regra |
|---|---|
| mesma chave achada em outro endpoint | o endpoint antigo é descartado (a chave é a identidade; vale o mais recente) |
| `onLost` | a presença só sai se não reaparecer em 3 s (anunciante que para e reanuncia não pisca) |
| aviso "saindo" | sai na hora (todas as entradas da chave) |
| busca reiniciada a cada 8 s | quem não reaparece 3 s depois, em 2 verificações seguidas, sai (~20 s) |
| chave **fixada** (marcada/recrutada/anfitrião do aceito) | a varredura não a remove; só `onLost` após a folga, "saindo" ou a queda da conexão |
| pedido de conexão com um jogador próximo em andamento | a busca não reinicia até ele terminar (as conexões já abertas não são afetadas) |

Cada remoção registra o motivo no log de depuração.

## Fotos

As fotos dos jogadores próximos vão **pela conexão de espera do recrutamento** (`PresencePhoto` logo depois do
`RecruitHello`, nos dois sentidos, uma vez por conexão), nunca por uma conexão avulsa: uma conexão avulsa para a foto e a
de recrutamento usam o mesmo endpoint, e desconectar uma derrubava a outra. Jogador sem conexão de espera (fora da cota
de esperas, ou montagem fechada) aparece com a inicial.

## Tempos (§16.15)

| O quê | Tempo |
|---|---|
| nova tentativa de conexão de espera | 0,3, 1, 2, 4 s (depois 8 s) |
| `onLost` da presença até sair da lista | 3 s |
| debounce da montagem | 0,3 s |
| reingresso do recrutado aceito | 8 s |
| marcado pendente sumido da lista | 10 s |
| confirmações no Escolher posições | até 2 s |
| confirmações no fechar / Wi-Fi desligado | até 0,5 s |

## Diagnóstico (build debug, tag `GTrancaNearby`)

Sem nomes, fotos ou bytes; chaves abreviadas (2 caracteres). Linhas úteis: `rec state A->B` (transição do recrutado),
`rec mark/unmark/accepted/removed <motivo>`, `rec cfg send v=N to=K` / `rec cfg apply v=N` / `rec cfg stale v=N`,
`presence found/lost/dedupe/leaving/sweep miss N/remove <motivo>`, `restart ok/failed`.
