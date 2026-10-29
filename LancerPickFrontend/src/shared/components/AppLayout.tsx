import { NavLink, Outlet } from 'react-router'
import styles from './AppLayout.module.css'

export function AppLayout() {
  return (
    <div className={styles.layout}>
      <header className={styles.header}>
        <NavLink to="/" className={styles.brand}>랜서픽</NavLink>
        <nav className={styles.nav} aria-label="주 메뉴">
          <NavLink to="/" end>상품</NavLink>
          <NavLink to="/orders">거래</NavLink>
          <NavLink to="/login">로그인</NavLink>
        </nav>
      </header>
      <main className={styles.main}>
        <Outlet />
      </main>
    </div>
  )
}
