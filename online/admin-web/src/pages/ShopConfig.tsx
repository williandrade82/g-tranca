import { useEffect, useState, type FormEvent } from 'react'
import { apiFetch } from '../api/client'
import {
  ApiRequestError,
  type AdminShopConfigDto,
  type AdminShopConfigHistoryPage,
  type AdminShopGainAdjustRequest,
  type AdminShopOfferAdjustRequest,
  type AdminShopOfferConfigDto,
} from '../api/types'
import { useSession } from '../auth/SessionContext'
import { AppHeader } from '../components/AppHeader'
import { newOperationId } from '../api/uuid'

function formatDate(ms: number): string {
  return new Date(ms).toLocaleString('pt-BR')
}

function origem(source: 'DEFAULT' | 'OVERRIDE', sinceMs: number | null): string {
  if (source === 'DEFAULT') return 'valor padrão do jogo'
  return `ajustado desde ${sinceMs != null ? formatDate(sinceMs) : '—'}`
}

const OFFER_FIELD = /^oferta-(\d+)-(preco|quantidade)$/

/**
 * O histórico (`AdminShopConfigHistoryEntryDto.field`) usa a chave interna do economy-service
 * (`oferta-$index-preco`...): o `boosterId` SOZINHO não identifica a oferta (as nove ofertas repetem os mesmos
 * três boosters, cada um em três pacotes - §17.10/Shop.OFFERS), por isso o rótulo junta o índice da tabela de
 * ofertas acima com o booster, pra dar pra achar a linha certa.
 */
function labelField(field: string, offers: AdminShopOfferConfigDto[]): string {
  const match = OFFER_FIELD.exec(field)
  if (!match) return field === 'ganho-diario' ? 'Ganho diário' : field
  const index = Number(match[1])
  const offer = offers.find((o) => o.index === index)
  const campo = match[2] === 'preco' ? 'preço' : 'quantidade'
  return offer ? `Oferta ${index} (${offer.boosterId}) · ${campo}` : `Oferta ${index} · ${campo}`
}

const HISTORY_LIMIT = 20

/**
 * Tela "Loja e configuração" (docs/telas-admin-web.md, §17.10/§14.6/§18.7): preço/quantidade das nove ofertas e
 * o ganho diário, com histórico versionado (append-only no economy-service). O catálogo de boosters (quais
 * existem) e o presente diário NÃO estão aqui (fora do alcance combinado desta etapa - ver relatório). A LEITURA
 * é leitor+ (como as demais telas); só ADMINISTRADOR vê os botões de editar (mesma regra de "Equipe"), mas,
 * diferente de Equipe, aqui a PÁGINA em si é visível a leitor+ também (sem guarda de corpo de página).
 */
export function ShopConfig() {
  const { session, forceLogout } = useSession()
  const isAdmin = session?.role === 'ADMINISTRADOR'

  const [config, setConfig] = useState<AdminShopConfigDto | null>(null)
  const [configError, setConfigError] = useState<string | null>(null)
  const [configLoading, setConfigLoading] = useState(true)
  // So o historico reles depois de um ajuste: a config em si ja e atualizada OTIMISTAMENTE com a resposta do
  // POST (ver confirmEditOffer/confirmEditGain), sem precisar de uma nova chamada a GET /admin/shop-config.
  const [historyReloadToken, setHistoryReloadToken] = useState(0)

  const [historyOffset, setHistoryOffset] = useState(0)
  const [history, setHistory] = useState<AdminShopConfigHistoryPage | null>(null)
  const [historyError, setHistoryError] = useState<string | null>(null)

  const [editingOffer, setEditingOffer] = useState<AdminShopOfferConfigDto | null>(null)
  const [offerPrice, setOfferPrice] = useState('')
  const [offerQuantity, setOfferQuantity] = useState('')
  const [offerReason, setOfferReason] = useState('')
  const [offerOperationId, setOfferOperationId] = useState('')
  const [offerBusy, setOfferBusy] = useState(false)
  const [offerError, setOfferError] = useState<string | null>(null)
  // true depois da PRIMEIRA tentativa (com sucesso ou erro) desta abertura do dialogo: o economy-service
  // deduplica cada CAMPO por (chave, operationId) separadamente (ver EconomyStore.writeConfig), entao mudar o
  // preco OU acrescentar a quantidade numa 2a tentativa com a MESMA chave aplicaria essa mudanca de verdade mas
  // sem conseguir gravar na auditoria do admin-api (que deduplica por operationId inteiro, nao por campo) - uma
  // mudanca real sem rastro de quem/por que. Trava os valores depois da 1a tentativa; só Cancelar (que gera uma
  // chave NOVA ao reabrir) permite editar de novo.
  const [offerAttempted, setOfferAttempted] = useState(false)

  const [editingGain, setEditingGain] = useState(false)
  const [gainAmount, setGainAmount] = useState('')
  const [gainReason, setGainReason] = useState('')
  const [gainOperationId, setGainOperationId] = useState('')
  const [gainBusy, setGainBusy] = useState(false)
  const [gainError, setGainError] = useState<string | null>(null)
  const [gainAttempted, setGainAttempted] = useState(false)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setConfigLoading(true)
      setConfigError(null)
      try {
        const response = await apiFetch<AdminShopConfigDto>('/admin/shop-config', { token: session?.token })
        if (!cancelled) setConfig(response)
      } catch (e) {
        if (cancelled) return
        if (e instanceof ApiRequestError && e.status === 401) {
          forceLogout()
          return
        }
        setConfigError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
      } finally {
        if (!cancelled) setConfigLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [session?.token, forceLogout])

  useEffect(() => {
    let cancelled = false
    async function load() {
      setHistoryError(null)
      try {
        const params = new URLSearchParams({ limit: String(HISTORY_LIMIT), offset: String(historyOffset) })
        const response = await apiFetch<AdminShopConfigHistoryPage>(`/admin/shop-config/history?${params}`, { token: session?.token })
        if (!cancelled) setHistory(response)
      } catch (e) {
        if (cancelled) return
        if (e instanceof ApiRequestError && e.status === 401) {
          forceLogout()
          return
        }
        setHistoryError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [session?.token, forceLogout, historyOffset, historyReloadToken])

  function openEditOffer(offer: AdminShopOfferConfigDto) {
    setEditingOffer(offer)
    setOfferPrice(String(offer.price.value))
    setOfferQuantity(String(offer.quantity.value))
    setOfferReason('')
    setOfferOperationId(newOperationId())
    setOfferError(null)
    setOfferAttempted(false)
  }

  async function confirmEditOffer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!editingOffer) return
    const price = Number(offerPrice)
    const quantity = Number(offerQuantity)
    if (!Number.isInteger(price) || !Number.isInteger(quantity)) {
      setOfferError('Preço e quantidade devem ser números inteiros.')
      return
    }
    if (price <= 0 || quantity <= 0) {
      setOfferError('Preço e quantidade devem ser maiores que zero.')
      return
    }
    // So manda o que de fato mudou: o economy-service grava uma linha NOVA no historico pra cada campo
    // recebido, mesmo que o valor seja igual ao atual (append-only, sem checar "mudou de verdade?") - mandar os
    // dois sempre criaria uma entrada de historico falsa pro campo que o operador nem quis tocar.
    const priceChanged = price !== editingOffer.price.value
    const quantityChanged = quantity !== editingOffer.quantity.value
    if (!priceChanged && !quantityChanged) {
      setOfferError('Mude o preço ou a quantidade.')
      return
    }
    setOfferBusy(true)
    setOfferError(null)
    setOfferAttempted(true)
    try {
      const updated = await apiFetch<AdminShopOfferConfigDto>(`/admin/shop-config/offer/${editingOffer.index}`, {
        method: 'POST',
        token: session?.token,
        body: {
          price: priceChanged ? price : undefined,
          quantity: quantityChanged ? quantity : undefined,
          reason: offerReason,
          operationId: offerOperationId,
        } satisfies AdminShopOfferAdjustRequest,
      })
      setConfig((prev) => (prev ? { ...prev, offers: prev.offers.map((o) => (o.index === updated.index ? updated : o)) } : prev))
      setEditingOffer(null)
      setHistoryReloadToken((t) => t + 1)
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) {
        forceLogout()
        return
      }
      setOfferError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
    } finally {
      setOfferBusy(false)
    }
  }

  function openEditGain() {
    if (!config) return
    setEditingGain(true)
    setGainAmount(String(config.dailyGain.value))
    setGainReason('')
    setGainOperationId(newOperationId())
    setGainError(null)
    setGainAttempted(false)
  }

  async function confirmEditGain(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!config) return
    const amount = Number(gainAmount)
    if (!Number.isInteger(amount)) {
      setGainError('O ganho diário deve ser um número inteiro.')
      return
    }
    if (amount <= 0) {
      setGainError('O ganho diário deve ser maior que zero.')
      return
    }
    if (amount === config.dailyGain.value) {
      setGainError('Informe um valor diferente do atual.')
      return
    }
    setGainBusy(true)
    setGainError(null)
    setGainAttempted(true)
    try {
      const updated = await apiFetch<AdminShopConfigDto['dailyGain']>('/admin/shop-config/daily-gain', {
        method: 'POST',
        token: session?.token,
        body: { amount, reason: gainReason, operationId: gainOperationId } satisfies AdminShopGainAdjustRequest,
      })
      setConfig((prev) => (prev ? { ...prev, dailyGain: updated } : prev))
      setEditingGain(false)
      setHistoryReloadToken((t) => t + 1)
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) {
        forceLogout()
        return
      }
      setGainError(e instanceof ApiRequestError ? e.message : 'Algo deu errado. Tente novamente.')
    } finally {
      setGainBusy(false)
    }
  }

  const hasPrevHistory = historyOffset > 0
  const hasNextHistory = history != null && historyOffset + HISTORY_LIMIT < history.total

  return (
    <main className="accounts-page">
      <AppHeader title="Loja e configuração" />

      {configLoading && <p>Carregando…</p>}
      {configError && <p role="alert">{configError}</p>}

      {config && (
        <>
          <section aria-label="Ofertas da loja">
            <h2>Ofertas</h2>
            <table className="accounts-tabela">
              <thead>
                <tr>
                  <th>Oferta #</th>
                  <th>Booster</th>
                  <th>Preço</th>
                  <th>Quantidade</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {config.offers.map((offer) => (
                  <tr key={offer.index}>
                    <td>{offer.index}</td>
                    <td>{offer.boosterId}</td>
                    <td>
                      {offer.price.value} moedas <small>({origem(offer.price.source, offer.price.sinceMs)})</small>
                    </td>
                    <td>
                      {offer.quantity.value} <small>({origem(offer.quantity.source, offer.quantity.sinceMs)})</small>
                    </td>
                    <td>
                      {isAdmin && (
                        <button type="button" aria-label={`Editar oferta ${offer.index} (${offer.boosterId})`} onClick={() => openEditOffer(offer)}>
                          Editar
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section aria-label="Ganho diário">
            <h2>Ganho diário</h2>
            <p>
              {config.dailyGain.value} moedas <small>({origem(config.dailyGain.source, config.dailyGain.sinceMs)})</small>
              {isAdmin && (
                <>
                  {' '}
                  <button type="button" aria-label="Editar ganho diário" onClick={openEditGain}>
                    Editar
                  </button>
                </>
              )}
            </p>
          </section>
        </>
      )}

      <section aria-label="Histórico de mudanças">
        <h2>Histórico de mudanças</h2>
        {historyError && <p role="alert">{historyError}</p>}
        {history && (
          <>
            {history.entries.length === 0 ? (
              <p>Nenhuma mudança registrada ainda (tudo no valor padrão do jogo).</p>
            ) : (
              <table className="accounts-tabela">
                <thead>
                  <tr>
                    <th>Quando</th>
                    <th>Campo</th>
                    <th>Valor</th>
                    <th>Valor anterior</th>
                    <th>Motivo</th>
                  </tr>
                </thead>
                <tbody>
                  {history.entries.map((entry) => (
                    <tr key={`${entry.field}-${entry.operationId}`}>
                      <td>{formatDate(entry.createdMs)}</td>
                      <td>{config ? labelField(entry.field, config.offers) : entry.field}</td>
                      <td>{entry.value}</td>
                      <td>{entry.previousValue ?? '—'}</td>
                      <td>{entry.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <div className="paginacao">
              <button type="button" disabled={!hasPrevHistory} onClick={() => setHistoryOffset((o) => Math.max(0, o - HISTORY_LIMIT))}>
                Anterior
              </button>
              <span>
                {history.total === 0 ? '0' : `${historyOffset + 1}–${Math.min(historyOffset + HISTORY_LIMIT, history.total)}`} de {history.total}
              </span>
              <button type="button" disabled={!hasNextHistory} onClick={() => setHistoryOffset((o) => o + HISTORY_LIMIT)}>
                Próxima
              </button>
            </div>
          </>
        )}
      </section>

      {editingOffer && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`Editar oferta de ${editingOffer.boosterId}`}
          className="dialogo-confirmacao"
          onKeyDown={(e) => {
            if (e.key === 'Escape' && !offerBusy) setEditingOffer(null)
          }}
        >
          <form onSubmit={confirmEditOffer}>
            <h3>Editar oferta de {editingOffer.boosterId}</h3>
            <p>
              Atenção: o app ainda mostra o preço padrão do jogo na tela da loja (não lê este valor do servidor) — o
              jogador será cobrado o valor novo sem ver o preço mudar na tela dele até essa parte do app ser ajustada.
            </p>
            <div className="campo">
              <label htmlFor="offer-price">Preço (moedas)</label>
              <input
                id="offer-price"
                type="number"
                value={offerPrice}
                onChange={(e) => setOfferPrice(e.target.value)}
                required
                autoFocus
                disabled={offerBusy || offerAttempted}
              />
            </div>
            <div className="campo">
              <label htmlFor="offer-quantity">Quantidade</label>
              <input
                id="offer-quantity"
                type="number"
                value={offerQuantity}
                onChange={(e) => setOfferQuantity(e.target.value)}
                required
                disabled={offerBusy || offerAttempted}
              />
            </div>
            <div className="campo">
              <label htmlFor="offer-reason">Motivo</label>
              <textarea
                id="offer-reason"
                value={offerReason}
                onChange={(e) => setOfferReason(e.target.value)}
                required
                minLength={3}
                maxLength={500}
                disabled={offerBusy || offerAttempted}
              />
            </div>
            {offerAttempted && offerError && (
              <p>Cancele e abra de novo pra mudar o preço, a quantidade ou o motivo; "Confirmar" só repete esta mesma tentativa.</p>
            )}
            {offerError && <p role="alert">{offerError}</p>}
            <div className="dialogo-botoes">
              <button type="submit" disabled={offerBusy}>
                Confirmar
              </button>
              <button type="button" onClick={() => setEditingOffer(null)} disabled={offerBusy}>
                Cancelar
              </button>
            </div>
          </form>
        </div>
      )}

      {editingGain && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Editar ganho diário"
          className="dialogo-confirmacao"
          onKeyDown={(e) => {
            if (e.key === 'Escape' && !gainBusy) setEditingGain(false)
          }}
        >
          <form onSubmit={confirmEditGain}>
            <h3>Editar ganho diário</h3>
            <p>
              Atenção: o app ainda mostra o ganho diário padrão do jogo na tela (não lê este valor do servidor) — o
              jogador vai receber o valor novo sem ver o número mudar na tela dele até essa parte do app ser ajustada.
            </p>
            <div className="campo">
              <label htmlFor="gain-amount">Ganho diário (moedas)</label>
              <input
                id="gain-amount"
                type="number"
                value={gainAmount}
                onChange={(e) => setGainAmount(e.target.value)}
                required
                autoFocus
                disabled={gainBusy || gainAttempted}
              />
            </div>
            <div className="campo">
              <label htmlFor="gain-reason">Motivo</label>
              <textarea
                id="gain-reason"
                value={gainReason}
                onChange={(e) => setGainReason(e.target.value)}
                required
                minLength={3}
                maxLength={500}
                disabled={gainBusy || gainAttempted}
              />
            </div>
            {gainAttempted && gainError && <p>Cancele e abra de novo pra mudar o valor ou o motivo; "Confirmar" só repete esta mesma tentativa.</p>}
            {gainError && <p role="alert">{gainError}</p>}
            <div className="dialogo-botoes">
              <button type="submit" disabled={gainBusy}>
                Confirmar
              </button>
              <button type="button" onClick={() => setEditingGain(false)} disabled={gainBusy}>
                Cancelar
              </button>
            </div>
          </form>
        </div>
      )}
    </main>
  )
}
