# Plano — Regras flexíveis por mesa

Etapa 1 da evolução (perfil com foto, moedas e online vêm depois). Fluxo de sempre:
`definition.md` → testes → código → `rules-auditor` → commits separados por etapa.

## Estrutura: base + diferenças

- `definition.md` continua a fonte da verdade e descreve **as regras-base** (as atuais).
  Cada ponto variável vira um **parâmetro nomeado** com o valor-base, citado na seção onde age
  (ex.: §10 "o morto vira monte — parâmetro `mortoBecomesStock`, base: sim").
- Um novo §14 lista os parâmetros e os **perfis da casa**, cada um descrito só pelas
  **diferenças** em relação à base.
- No código, o `RuleSet` é o perfil: `id`, `nome`, `versão`, `baseId` + valores. Perfil novo =
  cópia de outro com campos alterados (`copy(...)`). A tela mostra "o que muda em relação a
  <base>", e o jogador **duplica e personaliza** qualquer perfil.
- A partida/mesa guarda a cópia completa do `RuleSet` usado (imutável depois de aceita).

## Perfis da casa (2)

| Parâmetro | Clássica (base) | Tranca Paulista |
|---|---|---|
| 3 preto na mão no fim (§12.2) | −5 | **−100** |
| Morto vira monte quando o monte acaba (§10) | sim | **não** (monte acaba → partida sem vencedor, §10.2) |
| Grupos (§6.1) | qualquer valor | **só A, K, Q, J** (coringa permitido) |
| Exigência para bater (§11.1) | qualquer canastra | **canastra limpa** |
| Máximo de coringas por conjunto (§6.3) | 1 | 1 |
| Canastra limpa / suja (§12.1) | 200 / 100 | 200 / 100 |
| Pontuação-alvo (§13) | 3.000 | 3.000 |

Perfis da casa: por enquanto editáveis na tela de personalização (ajuste do dono).

## Mesa

- Mesa = perfil de regras + modo (individual/duplas) + meta (+ aposta, na etapa de moedas).
- O jogador vê as regras antes de entrar; ao aceitar, elas não mudam mais.
- **Mesas dos robôs**: algumas mesas abertas sorteadas entre os perfis da casa com pequenas
  variações (meta). O jogador entra numa delas ou cria a sua.

## Entregas testáveis

**Entrega A — motor e simulação (sem tela nova)**
1. `definition.md`: parâmetros nas seções §6.1, §6.3, §10, §11.1, §12; novo §14 com perfis e mesa.
2. Motor: campos no `RuleSet`, `@Serializable`, validação de combinações, perfis
   `CLASSICA` e `PAULISTA`; testes por parâmetro citando a seção.
3. Bots leem o `RuleSet` (guardar 3 preto pesa −100; grupos só de figuras; bater só com limpa).
4. `:sim` roda os dois perfis: zero estado ilegal, dificuldades mantêm a ordem de força.

**Entrega B — jogável no celular (APK)**
5. `:data`: perfis personalizados + perfis da casa editados; partida salva com o `RuleSet`
   (partida antiga = Clássica).
6. Tela "Mesas": mesas dos robôs + "Criar mesa" (escolhe perfil, modo, meta).
7. Tela "Regras": ver diferenças, duplicar, editar, salvar; resumo das regras durante o jogo.
8. `rules-auditor` + APK `MestreDaCanastra-<hash>-debug.apk`.

**Roteiro de teste no celular:** jogar uma Paulista e conferir: grupo de 7 bloqueado, grupo de K
com coringa aceito, batida com só canastra suja recusada, 3 preto na mão −100 na pontuação,
monte acabando sem virar morto encerra sem vencedor. Duplicar a Paulista, mudar um valor e jogar.
