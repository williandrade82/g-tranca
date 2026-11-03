-- S18.7: detalhe em texto livre do lancamento (o motivo digitado pelo operador ao ajustar saldo/booster,
-- "AJUSTE"). Nulo nos lancamentos antigos e nos automaticos (SALDO_INICIAL, COMPRA, APOSTA...) - so AJUSTE
-- usa isto hoje.
ALTER TABLE lancamento ADD COLUMN detalhe TEXT;
