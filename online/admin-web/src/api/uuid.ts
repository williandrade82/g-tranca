/**
 * Gera uma chave de idempotencia para as acoes de escrita do console (Sec18.7): cada dialogo de confirmacao
 * gera UMA vez, ao abrir, e reusa em toda nova tentativa (nunca uma chave nova por clique em "Confirmar") -
 * assim um retry depois de um timeout nao repete a acao nem desfaz o que outra pessoa fez nesse meio tempo.
 *
 * `crypto.randomUUID` so existe em contexto seguro (HTTPS ou localhost); acessando o servidor por IP da rede
 * local sem TLS (ver docs/plano-online.md, "rede local") ele fica `undefined`. `crypto.getRandomValues`
 * funciona em qualquer contexto, por isso o fallback.
 */
export function newOperationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
