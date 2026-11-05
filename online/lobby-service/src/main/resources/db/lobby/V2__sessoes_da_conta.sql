-- Plano 2.6: uma conta Google pode ter varias sessoes (um token por login/aparelho) com o mesmo id.
ALTER TABLE convidado DROP CONSTRAINT convidado_id_key;
CREATE INDEX convidado_id ON convidado (id);
