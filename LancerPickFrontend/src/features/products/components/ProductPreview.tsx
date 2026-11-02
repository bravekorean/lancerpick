import { useEffect, useRef } from 'react'
import { Button } from '../../../shared/components/Button'
import { formatWon } from '../../../shared/lib/format'
import type { ProductSummary } from '../types'
import styles from './ProductPreview.module.css'

/** 실제 상품 상세·주문 화면과 구분되는 임시 미리보기다. */
export function ProductPreview({ product, onClose }: { product: ProductSummary | null; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    if (product) dialog.current?.showModal()
    else dialog.current?.close()
  }, [product])
  return (
    <dialog ref={dialog} className={styles.dialog} onCancel={onClose} onClose={onClose} aria-labelledby="preview-title">
      {product && <>
        <span className={styles.label}>예시 상품 미리보기</span>
        <h2 id="preview-title">{product.title}</h2>
        <p>{product.category.name} · {product.seller.displayName}</p>
        <div className={styles.amount}>{formatWon(product.priceAmount)} <small>고정 가격 · {product.durationDays}일</small></div>
        <p className={styles.notice}>API 연결을 확인하기 위한 예시입니다. 실제 상품 상세와 주문 기능은 후속 구현 예정입니다.</p>
        <Button onClick={onClose}>확인</Button>
      </>}
    </dialog>
  )
}
