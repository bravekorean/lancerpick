import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { apiRequest, ApiError } from '../../shared/api/client'
import type { PageResponse } from '../../shared/api/types'
import { formatWon, formatDateTime } from '../../shared/lib/format'
import styles from './SettlementPage.module.css'

interface Settlement { id: string; orderId: string; grossAmount: number; feeRateBps: number; feeAmount: number; sellerAmount: number; createdAt: string }
interface Summary { completedCount: number; grossAmount: number; feeAmount: number; sellerAmount: number }
export function SettlementPage() {
  const [page, setPage] = useState(0)
  // wallet 하위 키로 두어 기존 로그아웃/계정 전환 캐시 정리에 참여한다.
  const list = useQuery({ queryKey: ['wallet', 'settlements', page], queryFn: ({ signal }) => apiRequest<PageResponse<Settlement>>(`/me/settlements?page=${page}&size=20`, { signal }), retry: false, gcTime: 0, staleTime: 0 })
  const summary = useQuery({ queryKey: ['wallet', 'settlement-summary'], queryFn: ({ signal }) => apiRequest<Summary>('/me/settlements/summary', { signal }), retry: false, gcTime: 0, staleTime: 0 })
  const error = list.error ?? summary.error
  if ([list.error, summary.error].some(e => e instanceof ApiError && e.status === 401)) return <section className={styles['settlements']}><h1>판매 수익</h1><p>로그인 후 본인의 판매 수익을 확인할 수 있습니다.</p><Link to="/login">로그인</Link></section>
  return <section className={styles['settlements']}>
    <Link to="/mypage">← 마이페이지</Link><h1>판매 수익</h1>
    <p>구매 확정된 거래의 수익입니다. 구매 적립금과 별도로 관리하며 출금 기능은 제공하지 않습니다.</p>
    {error ? <div role="alert"><p>판매 수익을 불러오지 못했습니다.</p><button onClick={() => { void list.refetch(); void summary.refetch() }}>다시 조회</button></div>
      : !list.data || !summary.data ? <p role="status">판매 수익을 불러오는 중입니다.</p>
      : <>
        <dl className={styles['settlement-summary']}><div><dt>완료 거래</dt><dd>{summary.data.completedCount}건</dd></div><div><dt>총 결제액</dt><dd>{formatWon(summary.data.grossAmount)}</dd></div><div><dt>수수료</dt><dd>{formatWon(summary.data.feeAmount)}</dd></div><div><dt>판매 수익</dt><dd>{formatWon(summary.data.sellerAmount)}</dd></div></dl>
        <h2>정산 내역</h2>
        {list.data.items.length === 0 ? <p>이 페이지에 정산 내역이 없습니다.</p> : <ul className={styles['settlement-list']}>{list.data.items.map(item => <li key={item.id}><strong>주문 #{item.orderId}</strong><time dateTime={item.createdAt}>{formatDateTime(item.createdAt)}</time><p>결제액 {formatWon(item.grossAmount)} · 수수료 {formatWon(item.feeAmount)}</p><b>판매 수익 {formatWon(item.sellerAmount)}</b></li>)}</ul>}
        <nav aria-label="정산 내역 페이지"><button disabled={page === 0} onClick={() => setPage(p => p - 1)}>이전</button><span>{page + 1}페이지</span><button disabled={page + 1 >= list.data.totalPages} onClick={() => setPage(p => p + 1)}>다음</button></nav>
      </>}
  </section>
}
