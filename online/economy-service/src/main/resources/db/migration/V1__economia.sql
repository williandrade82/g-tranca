-- §18.5 carteira e inventário online, com livro-razão. Cada mudança de saldo ou de booster é um lançamento com motivo e
-- chave de idempotência: repetir a mesma operação (falha de rede) não debita nem credita duas vezes.

CREATE TABLE conta (
    id          TEXT PRIMARY KEY,               -- id do convidado (fase 2.2) ou da conta Google (2.6)
    tipo        TEXT NOT NULL CHECK (tipo IN ('CONVIDADO', 'GOOGLE')),
    criada_em   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE carteira (
    conta_id        TEXT PRIMARY KEY REFERENCES conta (id),
    saldo           BIGINT NOT NULL CHECK (saldo >= 0),   -- §14.6 nunca negativo
    ultimo_resgate  BIGINT,                               -- §14.6 dia (época em dias) do último ganho diário
    ultimo_presente BIGINT                                -- §17.5 dia do último sorteio do presente
);

CREATE TABLE inventario (
    conta_id    TEXT NOT NULL REFERENCES conta (id),
    booster_id  TEXT NOT NULL,
    quantidade  INT  NOT NULL CHECK (quantidade >= 0),
    PRIMARY KEY (conta_id, booster_id)
);

CREATE TABLE lancamento (
    id              BIGSERIAL PRIMARY KEY,
    conta_id        TEXT NOT NULL REFERENCES conta (id),
    motivo          TEXT NOT NULL,       -- SALDO_INICIAL, GANHO_DIARIO, COMPRA, APOSTA, PREMIO, DEVOLUCAO, AJUSTE...
    moedas          BIGINT NOT NULL DEFAULT 0,
    booster_id      TEXT,
    quantidade      INT NOT NULL DEFAULT 0,
    referencia      TEXT,                -- mesa, oferta da loja...
    chave           TEXT NOT NULL,       -- idempotência por conta
    criado_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conta_id, chave)
);

CREATE INDEX lancamento_conta ON lancamento (conta_id, id);

-- §18.2 aposta retida ao iniciar o jogo, liquidada no fim (prêmio, perda ou devolução).
CREATE TABLE aposta (
    mesa_id     TEXT NOT NULL,
    conta_id    TEXT NOT NULL REFERENCES conta (id),
    valor       BIGINT NOT NULL CHECK (valor >= 0),
    estado      TEXT NOT NULL CHECK (estado IN ('RETIDA', 'LIQUIDADA')),
    PRIMARY KEY (mesa_id, conta_id)
);
