import type { BotLevel, Punicao, TableMode, TableState, TempoDeJogada, Visibilidade } from '../api/types'

// Rotulos em pt-BR compartilhados entre TablesList e TableDetail (tela Mesas, docs/telas-admin-web.md).

export const TABLE_STATE_LABEL: Record<TableState, string> = {
  ABERTA: 'Aberta',
  EM_JOGO: 'Em jogo',
  ENCERRADA: 'Encerrada',
}

export const TABLE_MODE_LABEL: Record<TableMode, string> = {
  INDIVIDUAL: 'Individual',
  DUPLAS: 'Duplas',
}

export const TEMPO_DE_JOGADA_LABEL: Record<TempoDeJogada, string> = {
  INDETERMINADO: 'Indeterminado',
  UM_MINUTO: '1 minuto',
  CINCO_MINUTOS: '5 minutos',
  QUINZE_MINUTOS: '15 minutos',
}

export const PUNICAO_LABEL: Record<Punicao, string> = {
  SEM_PUNICAO: 'Sem punição',
  PERDE_APOSTA: 'Perde a aposta',
}

export const VISIBILIDADE_LABEL: Record<Visibilidade, string> = {
  PRIVADA: 'Privada',
  PUBLICA: 'Pública',
}

export const BOT_LEVEL_LABEL: Record<BotLevel, string> = {
  FACIL: 'Fácil',
  MEDIO: 'Médio',
  DIFICIL: 'Difícil',
}
