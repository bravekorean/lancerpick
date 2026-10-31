// 이 기능의 서버 요청 함수를 구현합니다. 공통 요청 처리는 shared/api/client.ts에 둡니다.
import { apiRequest } from '../../shared/api/client'
import type { PageResponse } from '../../shared/api/types'
import type { Category, ProductSearch, ProductSummary } from './types'

// 예제 API만 사용한다. 실제 상품 API가 준비되면 이 경로와 조회 구현을 담당자와 함께 교체한다.
// 서버가 중단돼도 화면에서 샘플로 자동 대체하지 않으므로 연결 실패를 확인할 수 있다.
const catalogPath = '/demo'

export function getCategories(signal?: AbortSignal) {
  return apiRequest<Category[]>(`${catalogPath}/categories`, { signal })
}

export function getProducts(search: ProductSearch, signal?: AbortSignal) {
  const params = new URLSearchParams({ page: String(search.page), size: String(search.size) })
  if (search.keyword) params.set('keyword', search.keyword)
  if (search.categoryId) params.set('categoryId', search.categoryId)
  return apiRequest<PageResponse<ProductSummary>>(`${catalogPath}/products?${params}`, { signal })
}
