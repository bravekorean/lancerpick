import type { QueryClient } from '@tanstack/react-query'
import { clearCsrfToken } from '../../shared/api/client.ts'
import { clearWalletCache } from '../wallet/cache.ts'
import type { Member } from './types.ts'

/** 현재 로그인 회원 조회 결과의 캐시 이름표. 헤더·화면이 같이 쓴다. */
export const memberQueryKey = ['auth', 'me'] as const

/**
 * 로그인·로그아웃·계정 전환 직후 호출한다(개발 규칙 4절, 프런트 공통 가이드).
 * 1) 서버가 바꾼 CSRF 토큰을 다시 받도록 기존 토큰을 버린다.
 * 2) 이전 계정의 지갑·주문 등 조회를 중단하고 캐시를 지운다.
 * 3) 진행 중이던 로그인 상태 조회는 취소하고, 새 로그인 상태(member 또는 null)를 바로 반영한다.
 * ['auth'] 캐시는 지우지 않고 값만 바꾼다. 지우면 헤더가 보고 있던 캐시와 연결이 끊긴다.
 */
export async function resetAccountState(queryClient: QueryClient, member: Member | null) {
  clearCsrfToken()
  // 진행 중인 GET /api/me를 먼저 멈춘다. 멈추지 않으면 그 늦은 응답이 아래에서 넣은 새 상태를 덮어쓴다.
  await queryClient.cancelQueries({ queryKey: memberQueryKey })
  await clearWalletCache(queryClient)
  await queryClient.cancelQueries({ predicate: (query) => query.queryKey[0] !== 'auth' })
  queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' })
  queryClient.setQueryData(memberQueryKey, member)
}

/**
 * 로그인 후 돌아갈 주소. 같은 사이트 안의 경로만 허용한다.
 * "//evil.com" 이나 "https://..." 를 넣어 다른 사이트로 보내는 공격(오픈 리다이렉트)을 막는다.
 */
export function safeNextPath(next: string | null | undefined) {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return '/'
  if (next.startsWith('/login') || next.startsWith('/signup')) return '/'
  return next
}
