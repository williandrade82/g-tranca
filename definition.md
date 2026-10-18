# **Regras Oficiais do Jogo de Tranca**
### *Documento técnico para implementação digital*

> Esta é a **especificação final** usada pelo motor de regras. Cada regra tem um número de seção (ex.: `§5.4`), que deve ser citado nos testes automatizados que a verificam.
>
> O documento descreve as **regras-base** (perfil **Clássica**). Os pontos que podem variar de uma mesa para outra são **parâmetros de regra**, marcados no texto como *(parâmetro `nome`; base: valor)*. A lista completa e os perfis que alteram esses valores estão em §14.2 e §14.3; tudo que não é parâmetro vale igual em qualquer perfil.

---

## 📌 **1. Componentes do Jogo**

- O jogo utiliza **2 baralhos padrão**, sem curingas de fábrica (total de **104 cartas**).
- Cada baralho contém cartas de **2 a 10**, **Valete (J)**, **Dama (Q)**, **Rei (K)** e **Ás (A)**, nos quatro naipes tradicionais.
- Com o parâmetro `maxWildsPerMeld` em **0** (§6.3, §14.2), os **oito 2** (coringas) **saem do jogo**: o baralho fica com **96 cartas**.
- As cartas são embaralhadas aleatoriamente no início de cada partida, formando **um único monte**.

### **1.1. Lados**
- **Modo individual:** 2 jogadores (humano × máquina). Cada jogador é um **lado**.
- **Modo duplas:** 4 jogadores (humano + parceiro virtual × dupla virtual). Cada dupla é um **lado**; os parceiros sentam em posições opostas.
- Neste documento, **"lado"** significa o jogador (modo individual) ou a dupla (modo duplas).

---

## 📌 **2. Estrutura do Jogo**

- O jogo é composto por **múltiplas partidas**.
- O vencedor do jogo é o lado que atingir a **pontuação-alvo** (ver §14), padrão **3.000 pontos**, com a maior pontuação.

---

# **3. Distribuição das Cartas**

### **3.1. Mão dos jogadores**
- Cada jogador recebe **11 cartas**, distribuídas uma por vez, em ordem.

### **3.2. Mortos**
- São formados **2 mortos**, cada um com **11 cartas**.
- Os mortos são distribuídos **depois das mãos**, **uma carta por vez para cada morto, alternadamente** (1ª carta para o 1º morto, 2ª para o 2º morto, 3ª para o 1º morto…), até que cada morto tenha 11 cartas.
- Os mortos ficam na mesa como **dois montes separados**, com as cartas **viradas para baixo**: todos sabem que eles existem, mas ninguém pode ver suas cartas até pegá-los.

### **3.3. Monte**
- As cartas restantes formam o **monte**, virado para baixo (60 cartas no modo individual, 38 no modo duplas; sem os 2, §1: 52 e 30). Nada sai do monte na distribuição: as reposições de 3 vermelho (§6.5) só começam na vez de cada jogador (§3.5).

### **3.4. Lixo inicial**
- O lixo começa **vazio**. O primeiro jogador, portanto, compra obrigatoriamente do monte.

### **3.5. Três vermelhos recebidos na distribuição**
- Os 3 vermelhos recebidos ficam **na mão** do jogador (só ele os vê). **Nada é trocado na distribuição.**
- Um 3 vermelho só pode ser trocado **na vez do jogador, nunca antes**. No **início de cada vez** (e, para o primeiro jogador, no início da sua primeira jogada), **antes de comprar**, todos os 3 vermelhos da mão são **baixados automaticamente** e **repostos imediatamente** com cartas do topo do monte, incluindo as reposições em cadeia (ver §6.5).
- Assim, cada jogador troca os seus 3 vermelhos apenas quando chega a sua vez: o 1º jogador antes da sua primeira compra, o seguinte (sentido anti-horário, §4.2) no início da sua primeira vez, e assim por diante.
- As trocas são **públicas**: todos os jogadores são avisados, na ordem em que aconteceram, de **quem** baixou cada 3 vermelho. As cartas recebidas como reposição continuam ocultas (vão para a mão de quem trocou).
- O registro público de trocas também diz, de cada 3 vermelho baixado, se ele chegou como **reposição em cadeia** (veio do monte durante a própria troca) ou se já estava na mão. É informação pública (todos veem o 3 vermelho baixado) e serve para a interface encenar a troca na ordem certa.
- **Encenação na interface (não muda regra):** quando um jogador baixa **dois ou mais** 3 vermelhos de uma vez (os da mão no início da vez, §3.5, ou os que vieram no morto, §9.4), a troca é mostrada **de uma só vez**: os 3 vermelhos saem juntos da mão (ou do morto) para a área dos 3 vermelhos do lado, a segunda carta um pouco depois da primeira, e em seguida as reposições saem juntas do monte para a mão, também com a segunda um pouco depois. Um 3 vermelho que chega como reposição em cadeia (§6.5) é uma troca seguinte. A troca do **próprio jogador** é só um **aviso**, como a dos demais jogadores (sem botões nem confirmação), e fecha sozinha.
- **Histórico da vez:** os 3 vermelhos que um jogador baixou na sua vez mais recente (no início dela ou durante a jogada, inclusive os do morto e as reposições em cadeia) aparecem **em primeiro lugar** no resumo da última jogada dele na pílula do jogador (símbolo ♥ com as cartas) e no detalhe que abre ao tocar no jogador, antes das demais ações da vez.

---

# **4. Ordem de Jogada**

### **4.1. Primeiro jogador**
- Na **primeira partida** do jogo, o jogador inicial é escolhido aleatoriamente.
- Nas partidas seguintes, depois que a partida anterior terminar (com ou sem vencedor) e seus pontos forem computados, a nova partida é iniciada pelo **próximo jogador, no sentido anti-horário (§4.2), depois do que iniciou a partida anterior**.

### **4.2. Sentido**
- A ordem de jogada segue **sentido anti-horário**: o próximo a jogar é o jogador **à direita** de quem acabou de jogar.
- Assim, cada jogador recebe o descarte do jogador **à sua esquerda** (que joga antes dele) e descarta para o jogador **à sua direita** (que joga depois dele).

### **4.3. Estrutura de uma jogada**
Cada jogador, na sua vez, executa as ações na seguinte ordem:

1. **Comprar uma carta do monte**
   ou
   **Pegar o lixo**, se permitido (ver §5).

   São **alternativas exclusivas**, feitas **uma única vez**, no **início** da jogada: quem comprou do monte não pode pegar o lixo, e quem pegou o lixo não compra do monte. Nenhuma outra ação (baixar, acrescentar, descartar) pode ser feita antes desta etapa.

2. **Realizar qualquer ação, em qualquer ordem e quantas vezes quiser**, até descartar:
   - Baixar novos conjuntos.
   - Acrescentar cartas a conjuntos do seu lado já na mesa.

3. **Descartar uma carta**, finalizando sua jogada (ver §8), exceto quando bater baixando todas as cartas (ver §11).

> Antes da etapa 1, no **início da vez**, os 3 vermelhos que o jogador tem na mão são baixados e repostos **automaticamente** (§3.5, §6.5). Os que chegam durante a jogada (compra do monte, morto direto, reposição) também são trocados na hora. A troca não é uma ação do jogador.

---

# **5. Regras do Lixo**

### **5.1. Condições para pegar o lixo**
O jogador **só pode pegar o lixo** se conseguir **levar imediatamente para a mesa** a **carta do topo** (a última descartada pelo jogador anterior), de uma das formas:

- **Formando um novo conjunto** com a carta do topo e **pelo menos 2 cartas da própria mão** (um coringa da mão pode ser usado, respeitando o limite de 1 coringa por conjunto); ou
- **Acrescentando** a carta do topo a um **conjunto do seu lado já na mesa**, sozinha ou junto com cartas da própria mão, desde que o acréscimo todo seja válido (ex.: com 4-5-6♥ na mesa e 8♥ no topo, o jogador pode acrescentar 7♥ da mão e o 8♥ do topo juntos).

A carta do topo **não pode ir para a mão** e **não pode ser combinada com as demais cartas do lixo** para formar o conjunto. As "cartas da própria mão" são as que o jogador já tinha **antes de pegar o lixo**; as demais cartas do lixo só chegam à mão depois que o topo foi levado à mesa (§5.2).

### **5.2. Conteúdo do lixo**
Ao pegar o lixo de forma válida:

- O jogador recebe **todas as demais cartas do lixo**, que vão para a **mão**.
- A partir daí, essas cartas podem ser usadas normalmente na mesma jogada.

### **5.3. Travamento do lixo (3 preto)**
- Se o topo do lixo for um **3 preto**, ele **não pode ser levado à mesa**.
- Portanto, o lixo fica **travado** para o próximo jogador, que **não pode pegar o lixo** e deve **comprar do monte**.
- Depois que esse jogador descartar, o próximo pode pegar o lixo normalmente (se puder levar o novo topo à mesa), e o 3 preto vai para a mão junto com as demais cartas.

### **5.4. Coringa no topo**
- Se o topo do lixo for um **coringa (2)**, o lixo **pode ser pego** normalmente, seguindo §5.1, §5.2 e todas as regras de conjuntos com coringa (§6.3):
  - **Conjunto novo:** o coringa do topo com **pelo menos 2 cartas naturais da mão** (como o conjunto só pode ter 1 coringa, as cartas da mão não podem incluir outro coringa); ou
  - **Acréscimo:** o coringa do topo a um conjunto do seu lado **que ainda não tenha coringa**, sozinho ou junto com cartas da mão. Se o conjunto for uma canastra limpa, ela passa a ser **suja**.
- O jogador leva **todas as demais cartas do lixo** para a mão (§5.2).
- Pegar ou não esse lixo, sabendo que o coringa pode sujar um jogo limpo, é decisão de estratégia do jogador.

### **5.5. Lixo vazio**
- Não é possível pegar o lixo quando ele está vazio.

### **5.6. Visibilidade**
- O lixo é **aberto**: todas as cartas que o compõem ficam visíveis a todos os jogadores (humanos e virtuais).

---

# **6. Formação de Conjuntos**

### **6.1. Tipos de conjuntos**
Um conjunto pode ser:

- **Sequência** de cartas consecutivas do mesmo naipe.
- **Grupo** de cartas do mesmo número (naipes quaisquer, repetições permitidas). Os números que podem formar grupo são definidos pelo parâmetro `groupRanks` (base: **qualquer número**, de 4 a A); as opções estão em §14.2. Quando restrito, a restrição vale para todo grupo, inclusive o formado ao pegar o lixo (§5.1); o coringa continua podendo compor um grupo permitido (§6.3).

### **6.2. Regras gerais**
- Conjuntos devem ter **mínimo de 3 cartas**.
- **Nenhum 3** pode compor conjuntos.
- **Ás não é circular** e vale somente como carta alta:
  - Não existe A-2-3.
  - Não existe sequência que passe do Ás para o 2.
  - A sequência mais longa possível é **4-5-6-7-8-9-10-J-Q-K-A**.
- Cartas baixadas **não podem voltar à mão**.

### **6.3. Coringa (2)**
- O **2 é sempre coringa**; nunca é usado como carta natural. Não existe grupo de 2.
- O coringa substitui uma carta de uma sequência ou grupo (na sequência, conforme as situações abaixo).
- **Máximo de 1 coringa por conjunto** *(parâmetro `maxWildsPerMeld`; base: 1; opções 0 ou 1)*. Todo conjunto tem, portanto, pelo menos 2 cartas naturais. Com o parâmetro em **0**, não há coringa no jogo: os 2 saem do baralho (§1).
- **Posição do coringa na sequência:** é definida pelas cartas naturais do conjunto, nunca escolhida pelo jogador. Há três situações:
  - **Travado:** se falta exatamente um valor entre as cartas naturais, o coringa ocupa esse buraco e vale esse número. Ex.: em 5-2-7 o coringa vale 6; só se acrescentam cartas do 4 para baixo ou do 8 para cima.
  - **Solto:** se as cartas naturais não têm buraco, o coringa fica numa ponta **sem valor fixo** e pode servir em qualquer um dos lados. Ex.: em 6-7-2 o jogador pode acrescentar 8 (o coringa continua solto), 5 (continua solto), 5 e 4 (continua solto), apenas 4 (o coringa passa a valer 5 e fica travado) ou apenas 9 (o coringa passa a valer 8 e fica travado). Se a sequência já chega ao Ás (ex.: Q-K-A-2), o coringa só pode estar na ponta de baixo.
  - **Sem posição:** se as cartas naturais já formam a sequência completa 4-5-6-7-8-9-10-J-Q-K-A, o coringa permanece no conjunto sem representar nenhuma carta.
- **Coringa que "corre":** quando é baixada a carta natural do valor em que o coringa está travado, o buraco deixa de existir e o coringa volta a ficar **solto** (ou **sem posição**, se a sequência ficar completa). A carta natural é sempre aceita; o conjunto continua com coringa.
- Um coringa **pode** ser acrescentado a uma **canastra limpa** (respeitando o máximo de 1 coringa), inclusive à sequência completa 4…A; ela passa a ser **suja** (§7.2). Sujar ou não uma canastra limpa (por exemplo, para poder bater) é decisão de estratégia do jogador, não uma restrição da regra.
- No **grupo**, o coringa vale o número do grupo e não tem posição.

### **6.4. Conjuntos na mesa**
- Os conjuntos pertencem ao **lado**. No modo duplas, os parceiros compartilham os conjuntos e podem acrescentar cartas aos jogos um do outro.
- Um lado pode ter **mais de um grupo do mesmo número** na mesa, e pode criar um grupo novo mesmo que as cartas coubessem num grupo já existente. Manter um só grupo por número ou não é decisão de estratégia do jogador (ex.: abrir um segundo grupo para baixar um coringa que sobraria na mão).
- Um lado pode ter **mais de uma sequência do mesmo naipe**, desde que, ao ser **criada**, a nova sequência **não seja continuação** de uma sequência do mesmo naipe já na mesa. É continuação quando **todas as cartas da sequência nova poderiam ser acrescentadas, de uma vez, a uma sequência do mesmo naipe já na mesa do lado** (respeitando §6.2 e §6.3); nesse caso, as cartas devem ser acrescentadas à existente. **Exceções** (por estratégia do jogador, a nova sequência pode ser baixada como conjunto separado):
  - **Canastra limpa:** não se considera acrescentar o coringa da sequência nova a uma canastra limpa, porque sujá-la ou não é escolha do jogador (§6.3).
  - **Coringa da sequência existente:** não se considera mudar a situação do coringa da sequência que já está na mesa (§6.3). Se, para caber, o coringa dela teria de travar ou mudar de valor, não é continuação; se cabe com o coringa na mesma situação (continua solto, ou continua travado no mesmo valor), é continuação.

  Exemplos, com 4-5-6♥ na mesa:
  - 7-8-9♥ como conjunto novo: **proibido** (cabe na existente).
  - 8♥-9♥-2 como conjunto novo: **proibido** (cabe como 4-5-6-2-8-9, com o coringa valendo 7).
  - 8-9-10♥ como conjunto novo: **permitido** (falta o 7, não cabe).
  - 6♥'-7♥-8♥ (6 do 2º baralho) como conjunto novo: **permitido** (o 6 se repetiria, não cabe).
  - Com a canastra limpa 4…10♥ na mesa, J♥-Q♥-2 como conjunto novo: **permitido** (pela exceção acima; o jogador também pode, se preferir, acrescentar as cartas à canastra, que passa a ser suja).
  - Com a canastra limpa 4…10♥ na mesa, J♥-Q♥-K♥ como conjunto novo: **proibido** (cabe na canastra sem sujá-la).
  - Com 4-5-6-2♥ na mesa (coringa solto), 8♥-9♥-10♥ como conjunto novo: **permitido** (para caber, o coringa teria de travar no 7).
  - Com K-A-2♥ na mesa, 9♥-10♥-J♥ como conjunto novo: **permitido** (o coringa teria de travar na Q).
  - Com 4-5-6-2♥ na mesa (coringa solto), 7♥-8♥-9♥ como conjunto novo: **proibido** (cabe e o coringa continua solto).
  - Com 5-2-7♥ na mesa (coringa travado no 6), 8♥-9♥-10♥ como conjunto novo: **proibido** (cabe e o coringa continua travado no 6).
- **Sequências na mesa nunca se unem.** Ao acrescentar cartas, o jogador **indica em qual conjunto** elas entram. Se uma carta servir para mais de um conjunto (ex.: com 4-5-6♥ e 8-9-10♥ na mesa, o 7♥ pode prolongar qualquer um dos dois), o jogador escolhe o conjunto; as duas sequências continuam separadas, mesmo que passem a ficar encostadas.

### **6.5. Três vermelhos**
- **3 vermelho (copas ou ouros)**:
  - É baixado **automaticamente**, sozinho, **sempre na vez do jogador**: no início da vez, se já estava na mão (recebido na distribuição, §3.5, ou vindo do morto indireto, §9.4), ou na hora, durante a vez, se entra na mão pela compra do monte, pelo morto direto (§9.2) ou por reposição.
  - Vale **+100 ou –100 pontos** na mesa, conforme o lado tenha ou não canastra ao fim da partida, e soma ainda **+5** como carta na mesa (§12.1): no total, +105 ou –95.
  - Ao ser baixado, o jogador recebe **uma carta do monte** como reposição. Se a reposição também for 3 vermelho, o processo se repete (**reposição em cadeia**).
  - A troca é **pública**: todos são avisados de quem baixou o 3 vermelho e em que momento (no início da vez, §3.5, ou durante a jogada). A carta de reposição é oculta.
  - Se o monte estiver vazio, a reposição segue §10. Se não houver monte nem morto, o 3 vermelho é baixado **sem reposição**.
  - **Não pode ser descartado.**
- Baixar um 3 vermelho não conta como "baixar conjunto" e não altera as regras de morto e batida.

### **6.6. Três pretos**
- **3 preto (espadas ou paus)**:
  - Não pode ser baixado.
  - Pode ser descartado; se descartado, **trava o lixo** para o próximo jogador (ver §5.3).

---

# **7. Canastras**

### **7.1. Definição**
- Conjunto com **7 ou mais cartas**, limpa ou suja.

### **7.2. Tipos**
- **Canastra limpa**: sem coringa → **+200 pontos** *(parâmetro `cleanCanastaPoints`; base: 200)*
- **Canastra suja**: com coringa → **+100 pontos** *(parâmetro `dirtyCanastaPoints`; base: 100)*
- **Sem coringas** (§1): não existe canastra suja; toda canastra vale `cleanCanastaPoints` e é chamada apenas de **canastra** (a exigência de canastra limpa para bater, §11.1, não se aplica).

### **7.3. Crescimento**
- Uma canastra pode continuar recebendo cartas naturais.
- Uma canastra limpa pode receber coringa e passa a ser **suja** (ver §6.3).

---

# **8. Descarte**

- Toda jogada termina com **exatamente um descarte**, exceto a batida feita baixando todas as cartas (ver §11).
- Qualquer carta pode ser descartada, **exceto o 3 vermelho**. Coringas e 3 pretos podem ser descartados.
- A carta do topo do lixo que o jogador pegou nesta jogada vai obrigatoriamente para a mesa e, portanto, não pode ser descartada.
- **Ficar sem cartas é restrito:** o jogador só pode ficar com a mão vazia (baixando tudo ou descartando a última carta) se isso resultar em **pegar o morto** (§9) ou em **batida** (§11). Nos demais casos, a jogada é ilegal.
- **Manter cartas para descartar:** quando ficar sem cartas não for permitido, as jogadas de baixar conjuntos, acrescentar cartas e pegar o lixo devem deixar **pelo menos 2 cartas na mão** (uma para descartar e outra que permanece). Ex.: sem morto disponível e sem canastra, um jogador com 3 cartas não pode baixar 2 delas.
- **Última carta sem reposição:** se o jogador ficar com apenas 1 carta porque um 3 vermelho comprado não pôde ser reposto (sem monte e sem morto, §6.5/§10) e não puder bater descartando essa carta, a **partida termina sem vencedor** (§11.2).

---

# **9. Mortos**

### **9.1. Direito ao morto**
- Cada lado pode pegar **no máximo um morto** por partida.
- No modo duplas, o morto é pego pelo **primeiro jogador da dupla** que ficar sem cartas; o parceiro não pega outro.
- Um morto só está **disponível** enquanto não tiver sido pego por nenhum lado nem transformado em monte (§10).

### **9.2. Morto direto**
- Se o jogador **baixar todas as cartas da mão** durante a jogada (antes do descarte), seu lado ainda não tiver morto e houver morto disponível:
  - O jogador pega o morto **imediatamente** e **continua jogando** (volta à etapa 2 de §4.3), podendo baixar cartas e conjuntos antes de descartar.
  - Pode inclusive **bater na mesma jogada**, se cumprir §11.

### **9.3. Morto indireto**
- Se o jogador **descartar a última carta da mão**, seu lado ainda não tiver morto e houver morto disponível:
  - O jogador pega o morto e **joga com ele somente na sua próxima vez**.

### **9.4. 3 vermelhos no morto**
- No **morto direto** (§9.2), os 3 vermelhos que vierem no morto são baixados e repostos automaticamente, na hora (§6.5).
- No **morto indireto** (§9.3), eles ficam na mão e são baixados e repostos no **início da próxima vez** do jogador, como os recebidos na distribuição (§3.5).

### **9.5. Sem morto disponível**
- Se o lado ainda não pegou morto e **não há morto disponível** (os dois foram pegos pelo outro lado ou um deles virou monte, §10), o lado **pode bater sem ter pego o morto**, desde que cumpra o restante de §11.1 (a canastra exigida para bater). Vale nos dois modos (individual e duplas).
- A batida segue a pontuação normal (§12): o lado que bate ganha os **+100 da batida** (§12.1) e, por não ter pego morto, sofre os **–100 de morto não pego** (§12.2); um compensa o outro.
- Sem a canastra exigida para bater, o jogador **não pode ficar sem cartas** (§8), como antes.

---

# **10. Monte Esgotado**

**Morto vira monte assim que o monte acaba** *(com `mortoBecomesStock` = sim)*: o morto não espera a próxima compra.
- Se o monte ficar vazio durante uma jogada (compra da última carta ou reposição de 3 vermelho), o morto disponível vira o novo monte **assim que essa jogada termina**, com o descarte, antes da vez do próximo jogador.
- Se o monte ficar vazio no início da vez (reposição dos 3 vermelhos, §3.5), o morto vira monte **antes da compra** desse jogador.
- Se a partida terminar nessa jogada (batida), nada muda.

Quando for necessário comprar do monte (compra da jogada ou reposição de 3 vermelho) e ele estiver vazio:

1. Se houver **morto disponível**, ele se torna o **novo monte** (se houver dois, apenas um deles; o outro continua disponível como morto) *(parâmetro `mortoBecomesStock`; base: sim)*. Com o parâmetro em **não**, nenhum morto vira monte: segue-se direto o item 2, como se não houvesse morto disponível para isso (os mortos continuam disponíveis para serem pegos, §9).
2. Se não houver morto disponível (ou ele não puder virar monte, item 1):
   - **Reposição de 3 vermelho:** o 3 vermelho fica sem reposição.
   - **Compra da jogada:** o jogador pode pegar o lixo, se for permitido (§5). Caso não possa ou não queira, a **partida termina sem vencedor**.

---

# **11. Batida e Fim da Partida**

### **11.1. Condições para bater**
O jogador bate, encerrando a partida com vitória do seu lado, quando:

1. Seu **lado já pegou o morto** (no modo duplas, vale o morto pego por qualquer um dos parceiros) **ou não há mais morto disponível** (§9.5);
2. Seu **lado tem pelo menos uma canastra** na mesa (limpa ou suja) *(parâmetro `goOutRequiresCleanCanasta`; base: não)*. Com o parâmetro em **sim**, é preciso ter **pelo menos uma canastra limpa**; canastras sujas não permitem bater. Em todo o documento, "a canastra exigida para bater" refere-se a esta condição (inclusive em §8 e §9.5); e
3. O jogador fica **sem cartas na mão**, de uma das formas:
   - **baixando todas as cartas** (sem descarte); ou
   - **descartando a última carta**.

### **11.2. Fim sem vencedor**
A partida termina sem vencedor apenas nos casos de:
- §10: monte esgotado, sem morto disponível e sem possibilidade de pegar o lixo (ou o jogador não quer pegá-lo);
- §8: jogador com apenas 1 carta após 3 vermelho sem reposição, sem poder bater.

---

# **12. Pontuação da Partida**

Ao final de cada partida (com ou sem vencedor), cada lado soma:

### **12.1. Pontos na mesa**
- **3 vermelho** baixado na mesa: **+100** cada, **se o lado tiver pelo menos uma canastra (limpa ou suja) na mesa** ao fim da partida.
  - Se o lado **não tiver canastra** na mesa, cada 3 vermelho baixado vale **–100** (penalidade).
  - Vale com ou sem vencedor da partida.
- **Canastra limpa**: +200 cada
- **Canastra suja**: +100 cada
- **Batida**: +100 (somente o lado vencedor; não se aplica a partidas sem vencedor)
- **Cartas na mesa**: depois dos pontos especiais acima, **cada carta baixada na mesa** do lado soma o seu valor, com a mesma tabela das cartas na mão (§12.2), porém **positiva**:
  - **3 vermelho**: +5 (além do ±100 acima)
  - **4 a 10**: +8
  - **J, Q, K, A**: +10
  - **Coringa (2)**: +10
  - Não há 3 preto na mesa (nenhum 3 compõe conjuntos, §6.2).
  - Contam todas as cartas de todos os conjuntos do lado (canastras ou não) e os 3 vermelhos baixados, sempre (com ou sem vencedor, com ou sem canastra).
  - Exemplo: canastra limpa 4-5-6-7-8-9-10 de copas = 200 (canastra limpa) + 7 × 8 (cartas) = **256**.

### **12.2. Penalidades (negativas)**
- **Morto não pego**: –100 para cada lado que terminar a partida sem ter pego um morto — inclusive o lado que bate quando não havia mais morto disponível (§9.5).
- **Cartas na mão**, de todos os jogadores do lado:
  - **3 vermelho**: –5
  - **3 preto**: –5 *(parâmetro `handBlackThreePoints`; base: –5)*
  - **4 a 10**: –8
  - **J, Q, K, A**: –10
  - **Coringa (2)**: –10

### **12.3. Pontuação em duplas**
- Os pontos dos dois parceiros são **somados** no total da dupla.

---

# **13. Condições de Vitória do Jogo**

- Ao final de cada partida, se algum lado tiver pontuação total **maior ou igual (≥)** à pontuação-alvo, o jogo termina.
- Se mais de um lado atingir a pontuação-alvo, vence quem tiver a **maior pontuação**.
- Se houver empate na maior pontuação, joga-se **nova partida** até desempatar.

### **13.1. Desistência**
- Durante o jogo, o jogador humano pode **desistir**, depois de confirmar a decisão (se cancelar, o jogo continua exatamente como estava). A opção não é oferecida na tela de pontuação da partida (§12).
- Ao desistir, o jogo termina imediatamente e o **outro lado** (o jogador virtual ou a dupla adversária) é declarado **vencedor**. A partida em andamento não é pontuada. O jogador **perde a aposta** (§14.6).

---

# **14. Parâmetros Configuráveis**

Definidos **antes do início do jogo**, na criação da mesa (§14.4); não mudam durante o jogo.

| Parâmetro | Valores | Padrão |
|---|---|---|
| Perfil de regras | um perfil da casa (§14.3) ou personalizado | Clássica |
| Pontuação-alvo | número inteiro positivo | a do perfil (**3.000** nos perfis da casa) |
| Modo | individual / duplas | o último modo escolhido (individual na primeira vez) |
| Dificuldade dos jogadores virtuais | fácil / médio / difícil | médio |
| Aposta da mesa (§14.6) | 50, 100, 250 ou 500 moedas | 50 |

### **14.1. Perfis (apresentação; não alteram nenhuma regra)**

- O **jogador** define o próprio **perfil**, de forma simples: **nome** (até 16 caracteres; vazio = "Você") e **avatar**. O perfil vale para todos os jogos e é lembrado entre eles.
- O avatar do jogador é uma **foto pessoal** escolhida da galeria do aparelho ou, sem foto, um **medalhão com a inicial do nome** e uma **cor** à escolha entre as da paleta do app. A foto é recortada em círculo a partir do centro, reduzida e guardada **só no aparelho**; ela aparece em todos os lugares onde aparece o avatar do jogador. O jogador pode trocar ou remover a foto (volta a valer o medalhão).
- Os **adversários e o parceiro** (jogadores virtuais) **não são configurados pelo jogador**: são os **personagens** de §14.5, sorteados **ao formar a mesa** (§14.4), antes de o jogador escolhê-la, e são exatamente os que jogam ("Continuar" mostra os mesmos personagens).
- Não existem gênero nem profissão nos perfis.
- A imagem e o nome do jogador (ou do personagem) aparecem de forma discreta na mesa e em **toda** comunicação do jogo: avisos, janelas de decisão, tela de pontos e declaração do vencedor.

### **14.2. Parâmetros de regra**

São os únicos pontos das regras que mudam de um perfil para outro. Cada um é citado na seção onde age.

| Parâmetro | Seção | Opções | Base (Clássica) |
|---|---|---|---|
| `groupRanks` — números que podem formar grupo | §6.1 | **qualquer número** (4 a A) / **só A, K, Q, J** / **nenhum** (grupos proibidos) | qualquer número |
| `maxWildsPerMeld` — máximo de coringas por conjunto | §1, §6.3 | 0 (sem coringas: os 2 saem do baralho) / 1 | 1 |
| `cleanCanastaPoints` — canastra limpa | §7.2, §12.1 | inteiro ≥ 0 | 200 |
| `dirtyCanastaPoints` — canastra suja | §7.2, §12.1 | inteiro ≥ 0 | 100 |
| `mortoBecomesStock` — morto vira monte quando o monte acaba | §10 | sim / não | sim |
| `goOutRequiresCleanCanasta` — bater exige canastra limpa | §11.1 | sim / não | não |
| `handBlackThreePoints` — cada 3 preto na mão no fim da partida | §12.2 | inteiro ≤ 0 | –5 |
| `targetScore` — pontuação-alvo | §13, §14 | inteiro > 0 | 3.000 |

- Um perfil com valor **fora das faixas** da tabela não pode ser salvo nem usado numa mesa. Qualquer combinação de valores dentro das faixas é válida (ex.: grupos proibidos e 0 coringas = só sequências naturais).
- Todas as demais regras deste documento são **fixas** em qualquer perfil (cartas por mão = 11, cartas por morto = 11, tamanho mínimo de conjunto = 3 e de canastra = 7, demais valores de pontuação etc.). No código, ficam no `RuleSet` com os valores-base e não são expostas ao jogador.

### **14.3. Perfis de regras**

- Um **perfil de regras** tem **identificador, nome, versão e perfil de origem**, e define um valor para cada parâmetro de §14.2.
- **Perfis da casa** (fornecidos pelo app), descritos pelas diferenças em relação à base:

| Parâmetro | Clássica (base) | Tranca Paulista |
|---|---|---|
| `groupRanks` | qualquer número | **só A, K, Q, J** (com ou sem coringa) |
| `maxWildsPerMeld` | 1 | 1 |
| `cleanCanastaPoints` / `dirtyCanastaPoints` | 200 / 100 | 200 / 100 |
| `mortoBecomesStock` | sim | **não** (monte acaba → §10 item 2: pega o lixo ou a partida termina sem vencedor) |
| `goOutRequiresCleanCanasta` | não | **sim** |
| `handBlackThreePoints` | –5 | **–100** |
| `targetScore` | 3.000 | 3.000 |

- **Perfis personalizados:** o jogador pode **duplicar** qualquer perfil (da casa ou personalizado), dar um nome e alterar os parâmetros de §14.2. A origem fica registrada, e o app mostra o que difere dela.
- No editor das regras personalizadas, o jogador pode **começar a partir** dos valores de qualquer perfil da casa e ajustá-los (os perfis da casa em si não mudam).
- Alterar um perfil **não afeta** jogos já iniciados: cada jogo guarda uma cópia completa das regras com que começou (§14.4).

### **14.4. Mesa**

- Uma **mesa** reúne o perfil de regras, a pontuação-alvo e o modo (individual ou duplas).
- Antes de entrar numa mesa, o jogador pode **ver as regras** (as diferenças em relação à Clássica). Ao entrar, as regras são aceitas e **não mudam mais** até o fim do jogo; o jogo salvo ("Continuar") retoma com as mesmas regras.
- Uma mesa reúne também a **dificuldade** dos jogadores virtuais e a **aposta** (§14.6).
- **Mesas dos jogadores virtuais:** em "Novo jogo", o app lista algumas mesas já abertas por jogadores virtuais, cada uma com **perfil da casa**, **modo**, **dificuldade**, **pontuação-alvo** (1.500, 2.000 ou 3.000) e os **personagens que a ocupam** (§14.5), tudo sorteado ao montar a lista. A mesa de **duplas** mostra os **três** personagens (adversário à esquerda, parceiro e adversário à direita) com um retrato pequeno de cada um; a de **individual**, o personagem adversário. Tocar numa mesa mostra as **regras da mesa** e os personagens; o jogador **inicia o jogo** ou volta. **O jogo começa com exatamente os personagens da mesa escolhida**: nada é sorteado de novo ao iniciar.
- **Criar mesa:** o jogador escolhe dificuldade, modo, regras (**Clássica**, **Tranca Paulista** ou **Personalizada**) e pontuação-alvo (padrão 3.000). Ao criar, as vagas de parceiro e adversários são preenchidas por personagens sorteados nesse momento (§14.5) e abre a **tela da mesa**, a mesma das mesas abertas pelos jogadores virtuais, com os personagens sorteados e as regras. Ali o jogador **inicia o jogo**, **sorteia de novo** (novos personagens, mesma configuração, quantas vezes quiser) ou volta à criação. O jogo começa (distribuição das cartas, §3) com exatamente os personagens mostrados.
- **Regras personalizadas do jogador:** cada jogador tem **um** perfil personalizado (origem: Clássica na primeira vez), que é o seu padrão ao escolher "Personalizada". Ele é editado no **Perfil** do jogador ("Meu padrão para regras personalizadas") ou em "Criar mesa": tocar em "Personalizada" sempre abre a tela "Personalizar regra" (mesmo já escolhida); ao salvar, volta-se à criação da mesa com "Personalizada" escolhida; ao voltar sem salvar, a escolha anterior é mantida. Os parâmetros editáveis são os de §14.2, exceto a pontuação-alvo, escolhida na mesa.


### **14.5. Personagens (jogadores virtuais)**

Os jogadores virtuais são **4 perfis de robô**, cada um com **visual**, **estilo de jogo**, **nome** e **bordão** próprios, e **3 níveis**.

| Perfil | Código | Visual | Estilo de jogo |
|---|---|---|---|
| Jovem | `JOVEM` | adolescente, pele azulada, traços robóticos, fone de ouvido e boné | habilidoso e sagaz |
| Sóbrio | `SOBRIO` | adulto maduro (nem jovem nem idoso), mais volumoso, pele azul com detalhes esbranquiçados envelhecidos, adornos dourados, olhos cansados | sóbrio e conservador |
| Alternativo | `ALTERNATIVO` | pele esverdiada, traços arredondados, braços mais longos, colar natural marrom, colete marrom-escuro e chapéu de palha | ousado, procura sentido oculto em cada descarte |
| Atleta | `ATLETA` | adulto musculoso de feições humanizadas, pele laranja, bandana e regata | agressivo: pressiona o adversário |

- **Nome e bordão:** cada perfil tem **um nome único e fixo**, o mesmo em qualquer nível, e um **bordão** exibido como texto decorativo: **Jovem — Bolt** ("Bora fechar essa mesa!"), **Sóbrio — Domino** ("Cada carta tem seu propósito."), **Atleta — Turbo** ("Agora é pra valer!"), **Alternativo — Zen** ("Calma parceiro! A próxima carta pode mudar tudo!"). O modelo de identidade (nome, sobrenome, abreviação, posição) **continua previsto** para os jogadores online futuros, mas **não é sorteado** para os robôs.

- **Níveis:** **Iniciante** (dificuldade Fácil), **Maduro** (Médio) e **Sênior** (Difícil). Todos os personagens de uma mesa têm o nível da **dificuldade escolhida** (§14); nas mesas abertas por jogadores virtuais (§14.4), o nível é o da dificuldade da mesa. O nível aparece como insígnia de 1, 2 ou 3 estrelas junto da imagem.
- **Estilo × nível:** o nível define a força do jogador virtual; o estilo só muda **preferências entre jogadas legais** (por exemplo, quando pegar o lixo ou bater). **Nenhum estilo altera regra**, e o jogador virtual continua vendo apenas a informação visível a um jogador.
- **Sorteio ao formar a mesa:** 1 personagem no individual e 3 em duplas, com **perfis diferentes entre si**, sorteados com a semente da mesa **antes** de o jogador escolhê-la (nas mesas abertas pelos jogadores virtuais, §14.4) ou ao criar a mesa. O nome do personagem é o do seu perfil (acima), então não há nomes repetidos na mesa; o nome do jogador não influencia o sorteio. O perfil vale durante todo o jogo, e iniciar o jogo **não sorteia de novo**.
- **Como são chamados:** na **comunicação** do jogo (avisos, janelas de decisão, "Vez de", detalhe da última jogada, tela de pontos, declaração do vencedor), pelo **nome** com a **imagem** do personagem. Em **duplas**, os adversários levam a **posição em relação ao jogador**: `Nome (dir)` e `Nome (esq)`; o parceiro leva **só o nome**. No individual, só o nome. O jogador é "Você". Na **lista de mesas**, cada personagem aparece sob o seu retrato com o **bordão**, o **nome** e o **rótulo** da posição ("Adv. esq.", "Parceiro", "Adv. dir."), e o título da mesa individual é "Mesa de Nome" (o anfitrião, sem posição). Na **mesa de jogo** (o personagem já está sentado na sua posição), o nome vem **sem** a posição; só o parceiro leva a legenda "Parceiro". No **detalhe da última jogada** (o modal que abre ao tocar no jogador), o personagem aparece **inteiro** (sem recorte em círculo), um pouco maior, com o **bordão** logo abaixo da imagem e, depois, o nome.
- **Imagens:** cada personagem (perfil × nível) tem 4 poses: **apresentação** (painel de seleção de mesas, mostra o perfil), **vencedor**, **perdedor** e **pensador** (pequena, na mesa de jogo). Cada pose tem uma animação curta e cíclica (2 a 4 segundos).
- **Apresentação e animação:** a **lista de mesas** e a **tela da mesa** mostram o retrato de apresentação (e o nível em estrelas e a característica do perfil, que no individual vêm com o texto que "vende" o perfil); a mesa de **duplas** mostra os **três** personagens, em retratos menores. Na **tela "Regras da mesa"**, abaixo do título vem, centralizado, "Nível dos robôs:" com as estrelas e o nome do nível (Iniciante, Maduro ou Sênior), e à direita dessa linha o ícone de **sortear outros jogadores** (só em mesa criada pelo jogador); sob o retrato de cada robô vêm o **nome**, o **bordão** e a **descrição do estilo de jogo**. Em duplas, o **parceiro** aparece sozinho numa linha, centralizado e cerca de **40% maior**; os **adversários** vêm na linha seguinte, sob o título "Adversários", um ao lado do outro, **na posição em que ficam no jogo** (sem os rótulos "Adv. esq." e "Adv. dir."). Cada pose tem um movimento suave e um efeito próprio (brilho, confete, nuvem com chuva, pensamentos), sempre dentro do ciclo de 2 a 4 s.
- **Fim da partida e do jogo:** o anúncio e a tela final mostram os **dois lados completos**: os vencedores na pose de vencedor, com confete (o jogador, com a sua foto ou medalhão), e os perdedores na de perdedor, com a nuvem de chuva, sobre um fundo vermelho que pulsa devagar entre o escuro e o mais claro. Na **tela de pontos** o jogador aparece com a sua foto ou medalhão; cada personagem aparece no **rosto** de vencedor se o seu lado lidera o placar acumulado, de perdedor se está atrás, e de pensador se há empate (na tela final, o vencedor do jogo, inclusive por desistência).
- **"Continuar":** o sorteio depende só da semente do jogo, então "Continuar" mostra os mesmos personagens mesmo que o perfil do jogador seja renomeado.
### **14.6. Moedas e apostas**

As moedas são só do jogo contra os jogadores virtuais: **nunca há compra de moedas**; só se ganha jogando e pelo ganho diário. A carteira fica **no aparelho** (não é protegida contra alteração) e é independente de qualquer carteira online futura.

- **Carteira:** saldo inteiro, **nunca negativo**. O saldo inicial é de **1.000 moedas**.
- **Ganho diário:** uma vez por **dia** (data do aparelho), o jogador pode **resgatar 200 moedas**. Os dias sem resgate **não acumulam**. Com o resgate, o saldo nunca fica abaixo de 200. Com o relógio do aparelho atrasado (data anterior ou igual à do último resgate), o resgate não é liberado de novo.
- **Aposta da mesa:** cada mesa tem uma aposta de **50, 100, 250 ou 500 moedas**. **Todos** os jogadores da mesa (o jogador, o parceiro e os adversários) apostam o valor da mesa; as moedas dos jogadores virtuais não se acabam.
- **Entrar na mesa:** o jogador só entra se o saldo for **maior ou igual** à aposta da mesa; senão a mesa fica **bloqueada** ("saldo insuficiente"). Se o saldo mudou entre a tela da mesa e o início e o débito não passa, o jogo começa **sem aposta** (nada é debitado nem pago). A aposta é **debitada ao começar** o jogo. O jogo salvo ("Continuar") guarda a aposta já debitada, e retomá-lo não debita de novo.
- **Fim do jogo (§13):** os apostadores do lado **perdedor** perdem a aposta (já debitada); o **prêmio** é a soma das apostas dos perdedores, repartido entre os vencedores **na proporção da aposta de cada um**: cada vencedor recebe a **própria aposta** mais **prêmio × (aposta dele ÷ soma das apostas dos vencedores)**. O arredondamento para moedas inteiras é feito pelo **maior resto** (a moeda que sobra vai a quem tem a maior parte fracionária; empate: o primeiro na ordem dos assentos). A soma do que se recebe é sempre igual à soma das apostas.
- **Exemplos** (todos apostam a mesma quantia A): no **individual**, o vencedor recebe **2A** (a sua e a do outro); em **duplas**, cada vencedor recebe **2A** (a sua mais metade das duas apostas perdidas). Com apostas diferentes (uso futuro), 2 vencedores com 100 e 300 e 2 perdedores com 200 e 200: prêmio 400; o de 100 recebe 100 + 100 = 200 e o de 300 recebe 300 + 300 = 600.
- **Desistência (§13.1):** o jogador **perde a aposta**; nada é creditado.
- **Resultado em moedas:** o jogador vê, no fim do jogo, quanto ganhou ou perdeu (recebido menos a aposta), e as estatísticas mostram a soma desse resultado. O saldo e essa soma pertencem à **carteira**: zerar as estatísticas (contagem de jogos) não os altera. Se o aplicativo for fechado entre o débito da aposta e o primeiro salvamento do jogo, a aposta se perde (nunca se ganha sem apostar).

---

# **15. Resumo do Fluxo de Jogo**

Este resumo **não acrescenta regras**: ele organiza as seções anteriores, que prevalecem em caso de dúvida. O fluxo é apresentado em três níveis: o jogo (várias partidas), a partida (várias jogadas) e a jogada de um jogador.

### **15.1. Jogo**

```mermaid
flowchart TD
    A["Início do jogo: modo e pontuação-alvo (§14)"] --> B["Sorteia o primeiro jogador (§4.1)"]
    B --> C["Partida (§15.2)"]
    C --> D["Pontua a partida e soma aos totais de cada lado (§12)"]
    D --> E{"Algum lado tem total maior ou igual à pontuação-alvo? (§13)"}
    E -->|Não| F["Próxima partida, iniciada pelo jogador seguinte ao que iniciou a anterior (§4.1)"]
    C -.->|"Desistência do jogador humano, confirmada (§13.1)"| I["Fim do jogo: vence o outro lado; a partida em andamento não é pontuada"]
    E -->|Sim| G{"Há um único lado com a maior pontuação? (§13)"}
    G -->|Sim| H["Fim do jogo: vence esse lado"]
    G -->|Não, há empate| F
    F --> C
```

### **15.2. Partida**

```mermaid
flowchart TD
    A["Embaralha e distribui: 11 cartas por jogador, 2 mortos de 11 cartas, o restante no monte; lixo vazio; 3 vermelhos ficam na mão (§1, §3)"] --> C["Jogada do jogador da vez (§15.3), começando pelo primeiro jogador"]
    C --> D{"A partida terminou? (§11)"}
    D -->|"Sim: batida ou fim sem vencedor"| E["Fim da partida"]
    D -->|Não| F["Próximo jogador, no sentido anti-horário (§4.2)"]
    F --> C
```

### **15.3. Jogada**

```mermaid
flowchart TD
    A0["Início da vez: 3 vermelhos da mão são baixados e repostos automaticamente (§3.5, §6.5)"] --> A["Início da jogada"]
    A --> B{"Pegar o lixo? O lixo pode ser pego (§5) e o jogador quer"}
    B -->|Sim| C["Leva o topo à mesa e recebe as demais cartas do lixo (§5.1, §5.2)"]
    B -->|Não| D{"O monte tem cartas?"}
    D -->|Sim| E["Compra uma carta do monte (§4.3)"]
    D -->|Não| F{"Há morto disponível? (§10)"}
    F -->|Sim| G["Um morto vira o novo monte (§10)"]
    G --> E
    F -->|Não| Z1["Fim da partida sem vencedor (§10, §11.2)"]
    E --> H{"Ficou com 1 carta porque um 3 vermelho comprado não pôde ser reposto, e não pode bater? (§8)"}
    H -->|Sim| Z1
    H -->|Não| J["Baixa conjuntos e acrescenta cartas aos conjuntos do lado, em qualquer ordem e quantidade (§4.3, §6, §7)"]
    C --> J
    J --> K{"Ficou sem cartas antes de descartar?"}
    K -->|Não| L["Descarta uma carta (§8)"]
    K -->|Sim| M{"O lado ainda não pegou o morto e há morto disponível? (§9.5)"}
    M -->|Sim| N["Pega o morto direto e continua jogando (§9.2)"]
    N --> J
    M -->|Não| Z2["Batida: fim da partida com vitória do lado (§11.1)"]
    L --> O{"Ficou sem cartas?"}
    O -->|Não| P["Fim da jogada"]
    O -->|Sim| Q{"O lado ainda não pegou o morto e há morto disponível? (§9.5)"}
    Q -->|Sim| R["Pega o morto indireto, para jogar com ele na próxima vez (§9.3)"]
    R --> P
    Q -->|Não| Z2
```

### **15.4. Observações**
- **Ficar sem cartas** só é permitido quando leva a pegar o morto ou a bater (§8), e bater exige também que o lado tenha pelo menos uma canastra (§11.1). Sem morto disponível, o lado que ainda não pegou morto pode bater assim mesmo (§9.5). Como o motor só oferece jogadas válidas, os fluxogramas não mostram caminhos ilegais.
- **3 vermelhos** que entram na mão por qualquer via (compra, morto ou reposição) são baixados e repostos automaticamente, inclusive em cadeia (§6.5, §9.4); não são uma ação do jogador.
- **Duplas:** os parceiros compartilham os conjuntos e o morto do lado (§6.4, §9.1); o morto pego por um deles vale para a batida do outro (§11.1).
