import type { InputHTMLAttributes } from 'react'
import styles from './Common.module.css'

/** label·id·오류 설명의 연결은 사용하는 폼에서 지정한다. 기본 HTML 속성을 그대로 전달한다. */
export function Input({ className = '', ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={`${styles.input} ${className}`} {...props} />
}
