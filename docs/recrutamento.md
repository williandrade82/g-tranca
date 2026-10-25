# Recrutamento imediato: máquina de estados e sincronização (§16.2, §16.15)

Fonte da verdade das regras: `definition.md` §16.2 e §16.15. Este documento descreve **como** a tela "Iniciar novo jogo"
fica sincronizada entre aparelhos. Código: `net/.../Recruitment.kt`, `PresencePhotos.kt`, `app/.../NearbyTransport.kt`.

## Princípios

1. **O anúncio de presença só descobre; a conexão decide.** O anúncio leva o essencial e muda pouco: chave, nome,
   `inviting` (chaves marcadas e ainda sem resposta, só para o marcado achar o anfitrião) e `recruitedBy` (anfitrião que
   já recrutou o jogador, para os terceiros mostrarem "Recrutado"). Todo o resto (convite, cancelamento, aceite, recusa,
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
| marcar | cria o marcado `Pendente`, reanuncia a presença com a chave em `inviting` |
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
`Livre` -> (vê a própria chave em `inviting` de um anfitrião) `Conectando` -> (recebe `RecruitInvite`) `Convidado` ->
(Aceitar) `Aceito` -> fim.

- **Conectando** é interno (a tela mostra `Livre`): conecta ao anfitrião e envia `RecruitHello`; sem `RecruitInvite` em
  10 s, desconecta e repete com espera de 1, 2, 4 e 8 s (e 8 s dali em diante) **enquanto o anfitrião continuar
  listando a chave**. Sumiu a chave do anúncio: para.
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
| conexão de presença aberta (fotos/recrutamento) | a busca não reinicia |

Cada remoção registra o motivo no log de depuração.

## Fotos

Troca de fotos fora de mesa: o de **menor chave** conecta, os dois enviam `PresencePhoto` e quem abriu desconecta.
**Nunca** usa nem desconecta um endpoint de recrutamento: pares com conexão de recrutamento trocam as fotos **pela própria
conexão de recrutamento** (`PresencePhoto` logo depois do aceite, nos dois sentidos).

## Tempos (§16.15)

| O quê | Tempo |
|---|---|
| nova tentativa de conexão do convite | 1, 2, 4, 8 s (depois 8 s) |
| espera do `RecruitInvite` por tentativa | 10 s |
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
