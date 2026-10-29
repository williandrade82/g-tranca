# Plano: Loja de boosters

Status: **implementada** (branch `feature/loja`, empilhada em `feature/boosters`). Regras em `definition.md` §17.10; tela e modal em `docs/telas.md` (3.20, 3.18 e §4 Modais).

## O que é
O jogador troca as **moedas** ganhas nos jogos (§14.6) por **boosters** (§17.1). O acesso é pelo botão **Acessar loja** do Inventário (3.18). Vale só no aparelho, como a carteira e o inventário.

## Pedido original (28/10/2026)
- Ofertas fixas por ora: b1, b2 e b3, cada um em pacotes de **10 por 50**, **5 por 30** e **1 por 10** moedas (o valor é o total do pacote).
- A tela mostra a **imagem**, o **nome** e o **valor** de cada item; tocar abre um modal com informações do booster, a quantidade, o valor total, as moedas que o jogador tem e as que terá depois.
- A compra pode zerar o saldo, **nunca negativá-lo** (nesse caso não é feita).
- Confirmada, o booster já vale nos próximos jogos e o saldo na tela é atualizado.
- Imagens do painel em `docs/boosters/Loja` (ver `LEIA-ME.txt`).

## Decisões
1. **Valor = total do pacote**, não preço por unidade.
2. **Sem limite de estoque** na compra; o teto de 10 só vale para o presente diário (§17.5).
3. **Arte por oferta:** o ícone do booster que já existe (`boosterIconRes`). Arte própria por pacote pode vir depois.
4. **Lista em linhas** (uma por oferta, agrupadas por booster), em vez de grade: com o painel estreito no celular o texto fica legível.
5. **Topo e rodapé do painel fixos**; só o miolo (centro e laterais de madeira, com as ofertas) rola.
6. **Compra não é aposta:** não entra no resultado em moedas das estatísticas (§14.6).

## Desenho técnico
| Camada | O que | Onde |
|---|---|---|
| `:core-engine` | `ShopOffer`, `Shop.OFFERS` (as 9 ofertas), `Shop.balanceAfter` e `Shop.purchase` (puro: reaproveita `Wallet.debit`, que aceita saldo igual ao valor e recusa o que negativaria) | `Shop.kt`, `ShopTest.kt` |
| `:data` | `ShopRepository` (`DefaultShopRepository`), com `ShopLedger` (carteira) e `ShopStock` (inventário) | `ShopRepository.kt`, `Stats.kt`, `InventoryRepository.kt`, `GameData.shop` |
| `:app` | `ShopViewModel`, `ShopScreen` (painel + modal), botão `inventory-shop`, rota `ShopRoute` | `ui/shop/`, `AppNavHost.kt` |

### Compra segura entre dois arquivos
Saldo (arquivo das estatísticas) e inventário são DataStores separados, sem transação entre eles. A compra é feita em passos que podem ser retomados:
1. **Carteira:** numa só gravação, confere e debita o valor e grava `shop:pending` (`id|booster|quantidade`).
2. **Inventário:** soma a quantidade e anota o id em `boosters:shop_applied` (últimos 20); o mesmo id nunca soma duas vezes.
3. **Carteira:** limpa `shop:pending`.

`recoverPending()` termina uma compra interrompida e roda ao abrir a loja e antes de cada compra. Uma `Mutex` serializa as compras e a fila de gravações (`WriteQueue.app`) mantém a ordem com as demais gravações. O ViewModel ignora toque duplo em **Comprar**.

### Painel
Peças de `docs/boosters/Loja` convertidas para webp (`drawable-nodpi/shop_*`). Geometria (em pixels da imagem do topo, 653 de largura) no objeto `Panel` de `ShopScreen.kt`: as laterais repetem de 103 em 103, o centro de 69 em 69, a lateral direita é cortada só na moldura e o centro vai por baixo dela. Tudo é desenhado em pixels inteiros, com 1 px de sobreposição, para não aparecerem linhas de costura.

## Testes
- `ShopTest` (motor, §17.10): ofertas, débito, saldo zerado, saldo insuficiente e propriedade "nunca negativa".
- `ShopRepositoryTest` (dados): compra, saldo zerado, recusa, queda depois do débito, queda depois da soma e falha de gravação.
- `ShopViewModelTest` (app): lista, modal, saldo atualizado, saldo zerado, saldo insuficiente, toque duplo e falha.

## Pendente
- Ver a tela no celular (painel, textos em tela estreita, modal): não foi possível na sessão de desenvolvimento.
- Arte própria por pacote, se o usuário quiser.
- O PR deve mirar `feature/boosters` enquanto ela não for integrada.
