# Política de privacidade — Tranca-Bot

Última atualização: 2 de novembro de 2026

> **Revisar antes de publicar:** esta versão corrige o nome do app e as permissões reais (confirmadas em
> `app/src/main/AndroidManifest.xml`), mas o **modo online** (conta Google, mesas em servidor) ainda está em
> construção — quando ele for lançado, esta política precisa de uma seção própria para os dados que passarão a
> ficar no servidor (ver `docs/plano-online.md`). A publicação do app está adiada pelo usuário, então isto não é
> urgente, mas não pode ser esquecido quando o online sair.

O **Tranca-Bot** é um jogo de cartas para Android. Contra os robôs e no **multijogador local** (até 4 celulares
próximos, sem internet) ele funciona **totalmente offline**: nenhum dado sai do aparelho. O modo **online**
(em construção, opcional) é a única parte do app que vai depender de conta e de um servidor — ver a seção
"Modo online" abaixo.

## Permissões que o app pede e por quê

O multijogador local usa a tecnologia Nearby Connections do Android para achar outros celulares por perto
**sem internet**. Para isso, o Android exige estas permissões, só pedidas quando você liga o multijogador:

- **Wi-Fi e Bluetooth** (`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`,
  `BLUETOOTH_CONNECT`, e `BLUETOOTH`/`BLUETOOTH_ADMIN` em versões antigas do Android): usadas só para anunciar
  e descobrir mesas próximas e trocar as jogadas entre os celulares da mesma mesa.
- **Localização** (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`): exigida pelo próprio Android para a busca
  Bluetooth/Wi-Fi funcionar; o app **não lê nem guarda** sua localização, só usa a permissão para a busca.
- **Rede local** (`ACCESS_LOCAL_NETWORK`): mesma finalidade, exigida pelo Android recente.
- **Internet** (`INTERNET`): reservada para o modo online (ver abaixo); nos modos offline e multijogador local
  o app não faz nenhuma chamada de rede com essa permissão.

Nenhuma dessas permissões envia informação para fora do seu celular ou do celular dos jogadores na mesma mesa
local; não há anúncios, análise de uso (analytics) nem relatórios de falha enviados a terceiros; o app não
acessa contatos, câmera, microfone ou arquivos.

## Dados guardados no próprio aparelho

Para o jogo funcionar, ficam salvos **apenas no armazenamento interno do aplicativo**, no seu celular:

- o nome, o avatar e a foto de perfil que você escolher;
- preferências (som, último modo de jogo, ordem das cartas na mão);
- o jogo em andamento, para você continuar depois;
- as estatísticas de jogos (vitórias e derrotas por modo e dificuldade);
- a carteira de moedas locais, o inventário de boosters e as apostas da mesa (moeda virtual, nunca compra com
  dinheiro real e nunca sai do aparelho).

No **multijogador local**, durante uma partida, seu nome, avatar/foto e as jogadas públicas são enviados só
aos outros celulares **da mesma mesa**, direto entre os aparelhos (sem servidor, sem internet) — nada é
guardado por nós fora do seu celular.

Esses dados nunca saem do aparelho (fora da troca local acima). Você pode apagá-los a qualquer momento zerando
as estatísticas no app, ou em *Configurações do Android › Apps › Tranca-Bot › Armazenamento › Limpar dados*.
Desinstalar o app também os apaga.

## Modo online (em construção)

Quando o modo online for lançado, você poderá entrar com uma **conta Google** e jogar em mesas mantidas por um
servidor. Essa parte vai enviar dados ao servidor (identificação da conta, partidas e carteira online) —
**esta seção será substituída** por uma descrição completa antes do lançamento do online. Até lá, o modo não
está disponível e nada é enviado a nenhum servidor.

## Crianças

Fora do futuro modo online (que vai exigir conta Google, como qualquer app com login), o app não coleta dados
de ninguém, inclusive de crianças.

## Alterações

Se esta política mudar, a nova versão será publicada neste mesmo endereço, com a data atualizada.

## Contato

Dúvidas: williandrade@gmail.com
