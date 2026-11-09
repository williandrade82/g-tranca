-- §18.7 "Loja e configuracao": historico de preco/quantidade de cada oferta (§17.10) e do ganho diario (§14.6),
-- editaveis pelo administrador. Append-only (nunca UPDATE, igual a `lancamento`): o valor ATUAL de cada campo e a
-- linha mais recente (maior id) para a mesma `chave`. Sem nenhuma linha para uma `chave`, vale o default do
-- :core-engine (Shop.OFFERS / Coins.DAILY_GAIN), nunca duplicado aqui.

CREATE TABLE config_loja (
    id              BIGSERIAL PRIMARY KEY,
    chave           TEXT NOT NULL,       -- 'oferta-<indice>-preco', 'oferta-<indice>-quantidade', 'ganho-diario'
    valor           BIGINT NOT NULL,
    valor_anterior  BIGINT,              -- null = esta e a 1a mudanca (o anterior era o default, nunca gravado aqui)
    motivo          TEXT NOT NULL,       -- texto livre do operador, sempre obrigatorio (ao contrario de AJUSTE em lancamento)
    operacao_id     TEXT NOT NULL,       -- idempotencia por (chave, operacao_id)
    criado_em       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX config_loja_chave ON config_loja (chave, id DESC);
CREATE UNIQUE INDEX config_loja_idempotencia ON config_loja (chave, operacao_id);
