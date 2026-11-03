import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { ApiError } from '../../../shared/api/client'
import { formatWon } from '../../../shared/lib/format'
import { getOrders } from '../api'
import styles from './Orders.module.css'

export function OrderListPage() {
  const [side, setSide] = useState<'BUYER' | 'SELLER'>('BUYER')
  return <section className={styles.orders}><h1>거래 내역</h1><nav aria-label="거래 구분"><button aria-pressed={side === 'BUYER'} onClick={() => setSide('BUYER')}>구매 내역</button><button aria-pressed={side === 'SELLER'} onClick={() => setSide('SELLER')}>판매 내역</button></nav><OrderList key={side} side={side} /></section>
}
function OrderList({ side }: { side: 'BUYER' | 'SELLER' }) {
  const [page, setPage] = useState(0)
  const query = useQuery({ queryKey: ['wallet', 'orders', side, page], queryFn: ({ signal }) => getOrders(side, page, signal), retry: false, gcTime: 0 })
  if (query.error instanceof ApiError && query.error.status === 401) return <p>로그인 후 거래 내역을 확인할 수 있습니다. <Link to="/login">로그인</Link></p>
  if (query.isError) return <div role="alert"><p>거래 내역을 불러오지 못했습니다.</p><button onClick={() => { void query.refetch() }}>다시 조회</button></div>
  if (!query.data) return <p role="status">거래 내역을 불러오는 중입니다.</p>
  return <>{query.data.items.length === 0 ? <p>이 페이지에 거래 내역이 없습니다.</p> : <ul>{query.data.items.map(order => <li key={order.id}><Link to={`/orders/${order.id}`}>{order.productTitle}</Link><p>{order.counterparty.displayName} · {formatWon(order.priceAmount)} · {order.status}</p></li>)}</ul>}<nav aria-label="거래 내역 페이지"><button disabled={page === 0} onClick={() => setPage(p => p - 1)}>이전</button><span>{page + 1}페이지</span><button disabled={page + 1 >= query.data.totalPages} onClick={() => setPage(p => p + 1)}>다음</button></nav></>
}
