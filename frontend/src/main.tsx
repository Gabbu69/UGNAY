import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import * as Tooltip from '@radix-ui/react-tooltip'
import App from './App'
import './fonts.css'
import './index.css'
import './responsive.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
    mutations: { retry: false },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <Tooltip.Provider delayDuration={250}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </Tooltip.Provider>
    </QueryClientProvider>
  </StrictMode>,
)
