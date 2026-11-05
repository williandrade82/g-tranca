-- §18.2/§14.6 etapa 2.3: a coluna mesa_id guarda o id da APOSTA (unico por jogo; o id da mesa se repete).
-- Resultado da liquidação e datas, para a revisão do operador (§18.7).
ALTER TABLE aposta ADD COLUMN resultado TEXT CHECK (resultado IN ('PREMIO', 'DEVOLUCAO', 'PERDA'));
ALTER TABLE aposta ADD COLUMN recebido BIGINT CHECK (recebido >= 0);
ALTER TABLE aposta ADD COLUMN retida_em TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE aposta ADD COLUMN liquidada_em TIMESTAMPTZ;
CREATE INDEX aposta_retida ON aposta (estado) WHERE estado = 'RETIDA';
