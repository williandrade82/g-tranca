import { ApiRequestError, type ApiError } from './types'
import { clearSession } from './session'

/**
 * Chamada ao admin-api. Caminho relativo de proposito (Sec18.7, Main.kt:171-173): o NGINX do admin-web
 * fica no mesmo origin do admin-api em produção; em desenvolvimento o proxy do `vite.config.ts` cobre o resto.
 * Nunca usar CORS nem endereco absoluto aqui.
 */
export async function apiFetch<T>(
  path: string,
  options: { method?: string; body?: unknown; token?: string | null } = {},
): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (options.token) headers['Authorization'] = `Bearer ${options.token}`

  let response: Response
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    })
  } catch {
    throw new ApiRequestError(0, 'SEM_REDE', 'Não foi possível falar com o servidor. Verifique sua conexão.')
  }

  if (response.status === 401 && options.token) {
    // Sessao caiu (expirou ou foi revogada): sem sessao valida, nao ha o que fazer alem de voltar para Entrar.
    clearSession()
  }

  if (!response.ok) {
    let apiError: ApiError | null = null
    try {
      apiError = (await response.json()) as ApiError
    } catch {
      // corpo vazio ou invalido: segue com erro generico abaixo.
    }
    throw new ApiRequestError(
      response.status,
      apiError?.code ?? 'ERRO_DESCONHECIDO',
      apiError?.message ?? 'Algo deu errado. Tente novamente.',
    )
  }

  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}
