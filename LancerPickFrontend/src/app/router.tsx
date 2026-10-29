import { createBrowserRouter } from 'react-router'
import { LoginPage } from '../features/auth/pages/LoginPage'
import { ProductListPage } from '../features/products/pages/ProductListPage'
import { OrderListPage } from '../features/orders/pages/OrderListPage'
import { AppLayout } from '../shared/components/AppLayout'

export const router = createBrowserRouter([
  {
    element: <AppLayout />,
    children: [
      { index: true, element: <ProductListPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'orders', element: <OrderListPage /> },
      { path: '*', element: <h1>페이지를 찾을 수 없습니다.</h1> },
    ],
  },
])
